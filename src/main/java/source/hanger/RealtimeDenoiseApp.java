package source.hanger;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.File;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RealtimeDenoiseApp {

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
        Thread denoisedWriter = null;

        try {
            audioInputStream = AudioSystem.getAudioInputStream(new File(inputWavPath));
            AudioFormat format = audioInputStream.getFormat();

            if (format.getChannels() != 1 || format.getSampleRate() != 48000.0f || format.getSampleSizeInBits() != 16) {
                throw new UnsupportedAudioFileException("输入WAV文件必须是单声道、48kHz、16bit PCM 格式。");
            }

            audioWriter = new RealtimeAudioWriter(format, outputOriginalWavPath);
            streamProcessor = new DeepFilterNetStreamProcessor(modelPath, 100.0f, "trace", format, denoisedOutputQueue, true);

            denoisedWriterThread = new DenoisedAudioWriterThread(format, outputDenoisedWavPath, denoisedOutputQueue);
            denoisedWriter = new Thread(denoisedWriterThread, "DenoisedAudioWriterThread");
            denoisedWriter.start();

            int frameLength = streamProcessor.getFrameLength();
            int bytesPerFrame = format.getFrameSize();
            byte[] buffer = new byte[frameLength * bytesPerFrame];
            ByteBuffer byteBuffer = ByteBuffer.allocate(frameLength * bytesPerFrame);
            byteBuffer.order(format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

            streamProcessor.start();

            int bytesRead;
            while ((bytesRead = audioInputStream.read(buffer)) != -1) {
                if (bytesRead < buffer.length) {
                    break;
                }

                audioWriter.onOriginalAudioFrame(buffer, 0, bytesRead);

                streamProcessor.processAudioFrame(buffer);
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
                streamProcessor.stop();
            }
            if (audioWriter != null) {
                try {
                    audioWriter.close();
                } catch (Exception e) {
                    log.error("关闭 WAV 文件写入器失败: {}", e.getMessage(), e);
                }
            }
            if (denoisedWriterThread != null) {
                denoisedWriterThread.stop();
                try {
                    denoisedWriter.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("等待降噪音频写入线程结束时被中断: {}", e.getMessage());
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

        public DenoisedAudioWriterThread(AudioFormat format, String denoisedFilePath, BlockingQueue<byte[]> queue) throws IOException {
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
