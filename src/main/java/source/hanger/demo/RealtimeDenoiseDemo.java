package source.hanger.demo;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import lombok.extern.slf4j.Slf4j;
import source.hanger.DeepFilterNetStreamProcessor;
import source.hanger.WavFileWriter;

@Slf4j
public class RealtimeDenoiseDemo {

    private static final BlockingQueue<byte[]> denoisedOutputQueue = new ArrayBlockingQueue<>(500);

    public static void main(String[] args) {
        String modelPath = "models/DeepFilterNet3_onnx.tar.gz";
        String inputWavPath = "data/speech_with_noise_48k.wav";
        String outputOriginalWavPath = "out/original_audio.wav";
        String outputDenoisedWavPath = "out/denoised_audio.wav";

        AudioInputStream audioInputStream = null;
        DeepFilterNetStreamProcessor streamProcessor = null;
        RealtimeAudioWriter audioWriter = null;
        DenoisedAudioWriterThread denoisedWriterThread = null;
        Thread denoisedWriter;

        try {
            audioInputStream = AudioSystem.getAudioInputStream(new File(inputWavPath));
            AudioFormat format = audioInputStream.getFormat();

            if (format.getChannels() != 1 || format.getSampleRate() != 48000.0f || format.getSampleSizeInBits() != 16) {
                throw new UnsupportedAudioFileException("输入WAV文件必须是单声道、48kHz、16bit PCM 格式。");
            }

            audioWriter = new RealtimeAudioWriter(format, outputOriginalWavPath);
            streamProcessor = new DeepFilterNetStreamProcessor(modelPath, 100.0f, "trace", format, denoisedOutputQueue);

            denoisedWriterThread = new DenoisedAudioWriterThread(format, outputDenoisedWavPath, denoisedOutputQueue);
            denoisedWriter = new Thread(denoisedWriterThread, "DenoisedAudioWriterThread");
            denoisedWriter.start();

            int frameLength = streamProcessor.getFrameLength();
            int bytesPerFrame = format.getFrameSize();
            byte[] buffer = new byte[frameLength * bytesPerFrame];
            ByteBuffer byteBuffer = ByteBuffer.allocate(frameLength * bytesPerFrame);
            byteBuffer.order(format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

            // 启动 DeepFilterNetStreamProcessor 的内部处理线程
            streamProcessor.start();

            int bytesRead;
            while ((bytesRead = audioInputStream.read(buffer)) != -1) {
                if (bytesRead < buffer.length) {
                    break;
                }
                // 创建一个副本以避免并发修改问题，因为 buffer 会被重复使用
                byte[] bufferCopy = java.util.Arrays.copyOf(buffer, bytesRead);

                audioWriter.onOriginalAudioFrame(bufferCopy, 0, bytesRead);
                streamProcessor.processAudioFrame(bufferCopy);
            }
            // 通知 DeepFilterNetStreamProcessor 输入已结束
            streamProcessor.signalEndOfInput();

            // 等待 DeepFilterNetStreamProcessor 完成所有处理
            while (streamProcessor.isRunning()) {
                try {
                    Thread.sleep(100); // 短暂休眠，等待处理完成
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("主线程在等待 DeepFilterNetStreamProcessor 完成处理时被中断: {}", e.getMessage());
                    break;
                }
            }
            log.info("DeepFilterNetStreamProcessor 已完成所有音频帧的处理。");

            // 确保 denoisedWriterThread 在所有降噪数据处理完后才停止
            denoisedWriterThread.stop(); // 通知写入线程停止
            try {
                denoisedWriter.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待降噪音频写入线程结束时被中断: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("实时降噪应用发生错误: {}", e.getMessage(), e);
        } finally {
            if (audioInputStream != null) {
                try {
                    audioInputStream.close();
                } catch (IOException e) {
                    log.error("关闭音频输入流失败: {}", e.getMessage(), e);
                }
            }
            if (streamProcessor != null) {
                streamProcessor.stop(); // 确保释放 DeepFilterNetStreamProcessor 资源
            }
            if (audioWriter != null) {
                try {
                    audioWriter.close();
                } catch (Exception e) {
                    log.error("关闭 WAV 文件写入器失败: {}", e.getMessage(), e);
                }
            }
        }
    }

    private static class RealtimeAudioWriter implements AutoCloseable {
        private final WavFileWriter originalWavWriter;

        public RealtimeAudioWriter(AudioFormat format, String originalFilePath) throws IOException {
            this.originalWavWriter = new WavFileWriter(format, originalFilePath);
        }

        public void onOriginalAudioFrame(byte[] audioBytes, int offset, int length) {
            try {
                originalWavWriter.write(audioBytes, offset, length);
            } catch (IOException e) {
                log.error("写入原始音频文件失败: {}", e.getMessage(), e);
            }
        }

        @Override
        public void close() throws Exception {
            originalWavWriter.close();
        }
    }

    private static class DenoisedAudioWriterThread implements Runnable, AutoCloseable {
        private final WavFileWriter denoisedWavWriter;
        private final BlockingQueue<byte[]> queue;
        private final AtomicBoolean running = new AtomicBoolean(true);

        public DenoisedAudioWriterThread(AudioFormat format, String denoisedFilePath, BlockingQueue<byte[]> queue)
            throws IOException {
            this.denoisedWavWriter = new WavFileWriter(format, denoisedFilePath);
            this.queue = queue;
        }

        @Override
        public void run() {
            try {
                while (running.get() || !queue.isEmpty()) {
                    byte[] audioBytes = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (audioBytes != null) {
                        denoisedWavWriter.write(audioBytes, 0, audioBytes.length);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("DenoisedAudioWriterThread 写入线程中断: {}", e.getMessage());
            } catch (IOException e) {
                log.error("DenoisedAudioWriterThread 写入降噪音频文件失败: {}", e.getMessage(), e);
            } finally {
                try {
                    close();
                } catch (Exception e) {
                    log.error("DenoisedAudioWriterThread 关闭文件写入器失败: {}", e.getMessage(), e);
                }
            }
        }

        public void stop() {
            running.set(false);
        }

        @Override
        public void close() throws Exception {
            denoisedWavWriter.close();
        }
    }
}
