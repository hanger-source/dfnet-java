package source.hanger;

import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.*;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MicrophoneDenoiseApp {

    private static final BlockingQueue<byte[]> denoisedOutputQueue = new ArrayBlockingQueue<>(500);

    public static void main(String[] args) {
        String modelPath = "models/DeepFilterNet3_onnx.tar.gz";
        String outputOriginalWavPath = "out/microphone_original_audio.wav";
        String outputDenoisedWavPath = "out/microphone_denoised_audio.wav";

        AudioFormat format = new AudioFormat(48000.0f, 16, 1, true, false); // 48kHz, 16-bit, mono, signed, little-endian

        DeepFilterNetStreamProcessor streamProcessor = null;
        RealtimeAudioWriter audioWriter = null;
        DenoisedAudioWriterThread denoisedWriterThread = null;
        Thread denoisedWriter = null;
        SourceDataLine sourceDataLine = null;
        Thread playbackThread = null;
        // 移除 playbackQueue 的声明和初始化

        try {
            // 确保本地库路径已初始化
            DeepFilterNetLibraryInitializer.initializeNativeLibraryPath();

            audioWriter = new RealtimeAudioWriter(format, outputOriginalWavPath);
            // DeepFilterNetStreamProcessor 将自行管理麦克风输入 (isExternalInput = false)
            streamProcessor = new DeepFilterNetStreamProcessor(modelPath, 100.0f, "trace", format, denoisedOutputQueue, true);

            denoisedWriterThread = new DenoisedAudioWriterThread(format, outputDenoisedWavPath, denoisedOutputQueue);
            denoisedWriter = new Thread(denoisedWriterThread, "DenoisedAudioWriterThread");
            denoisedWriter.start();

            // 设置 SourceDataLine 用于实时播放降噪后的音频
            DataLine.Info sourceInfo = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(sourceInfo)) {
                log.error("扬声器输出不支持此音频格式: {}.", format);
                throw new LineUnavailableException("扬声器输出不支持此音频格式。");
            }
            sourceDataLine = (SourceDataLine) AudioSystem.getLine(sourceInfo);
            sourceDataLine.open(format, streamProcessor.getFrameLength() * format.getFrameSize() * 4); // 缓冲区大小
            sourceDataLine.start();

            // 播放线程
            SourceDataLine finalSourceDataLine = sourceDataLine;
            final DeepFilterNetStreamProcessor finalStreamProcessor = streamProcessor; // 确保 streamProcessor 是有效 final
            playbackThread = new Thread(() -> {
                try {
                    while (finalStreamProcessor.isRunning() || !denoisedOutputQueue.isEmpty()) { // 从 denoisedOutputQueue 获取数据
                        byte[] audioBytes = denoisedOutputQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (audioBytes != null) {
                            finalSourceDataLine.write(audioBytes, 0, audioBytes.length);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("播放线程被中断: {}", e.getMessage());
                } finally {
                    if (finalSourceDataLine != null) {
                        finalSourceDataLine.stop();
                        finalSourceDataLine.close();
                        log.info("SourceDataLine 已停止并关闭。");
                    }
                }
            }, "AudioPlaybackThread");
            playbackThread.start();

            // 当 isExternalInput 为 true 时，DeepFilterNetStreamProcessor 不启动内部捕获线程
            // streamProcessor.start(); // 移除此行

            int frameLength = streamProcessor.getFrameLength();
            int bytesPerFrame = format.getFrameSize();
            byte[] buffer = new byte[frameLength * bytesPerFrame];

            log.info("正在启动麦克风捕获和降噪处理，按 Ctrl+C 停止...");

            TargetDataLine targetDataLine = null;
            try {
                DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, format);
                if (!AudioSystem.isLineSupported(targetInfo)) {
                    log.error("麦克风输入不支持此音频格式: {}.", format);
                    throw new LineUnavailableException("麦克风输入不支持此音频格式。");
                }
                targetDataLine = (TargetDataLine) AudioSystem.getLine(targetInfo);
                targetDataLine.open(format, frameLength * format.getFrameSize() * 4); // 缓冲区大小
                targetDataLine.start();

                while (true) { // 持续捕获，直到用户停止
                    int bytesRead = targetDataLine.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        // 创建一个副本以避免并发修改问题，因为 buffer 会被重复使用
                        byte[] bufferCopy = java.util.Arrays.copyOf(buffer, bytesRead);
                        audioWriter.onOriginalAudioFrame(bufferCopy, 0, bytesRead); // 写入原始音频
                        streamProcessor.processAudioFrame(bufferCopy); // 降噪处理
                    }
                }
            } finally {
                if (targetDataLine != null) {
                    targetDataLine.stop();
                    targetDataLine.close();
                    log.info("TargetDataLine 已停止并关闭。");
                }
            }


        } catch (Exception e) {
            log.error("麦克风实时降噪应用发生错误: {}", e.getMessage(), e);
        } finally {
            if (streamProcessor != null) {
                streamProcessor.stop(); // 确保释放 DeepFilterNetStreamProcessor 资源
            }
            if (audioWriter != null) {
                try {
                    audioWriter.close();
                } catch (Exception e) {
                    log.error("关闭原始 WAV 文件写入器失败: {}", e.getMessage(), e);
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
            if (playbackThread != null) {
                playbackThread.interrupt();
                try {
                    playbackThread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("等待播放线程结束时被中断: {}", e.getMessage());
                }
            }
            // 不需要手动关闭 sourceDataLine，因为它在 playbackThread 的 finally 块中处理
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
