package source.hanger.demo;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

import lombok.extern.slf4j.Slf4j;
import source.hanger.jna.DeepFilterNetLibraryInitializer;
import source.hanger.processor.DeepFilterNetStreamProcessor;
import source.hanger.processor.agent.DeepFilterNetProcessingAgent; // 导入 DeepFilterNetProcessingAgent 以获取 AUDIO_FORMAT

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Slf4j
public class MicrophoneDenoiseDemo {

    public static void main(String[] args) {
        String outputOriginalWavPath = "out/microphone_original_audio.wav";
        String outputDenoisedWavPath = "out/microphone_denoised_audio.wav";

        // 移除 AudioFormat format = new AudioFormat(...) 的定义，直接使用 DeepFilterNetProcessingAgent.AUDIO_FORMAT
        // AudioFormat format = new AudioFormat(48000.0f, 16, 1, true, false); // 48kHz, 16-bit, mono, signed, little-endian

        DeepFilterNetStreamProcessor streamProcessor = null;
        CombinedAudioFrameWriter combinedAudioFrameWriter = null; // 使用 CombinedAudioFrameWriter

        try {
            // 确保本地库路径已初始化
            DeepFilterNetLibraryInitializer.initializeNativeLibraryPath();

            combinedAudioFrameWriter = new CombinedAudioFrameWriter(DeepFilterNetProcessingAgent.AUDIO_FORMAT, outputOriginalWavPath,
                outputDenoisedWavPath);
            streamProcessor = new DeepFilterNetStreamProcessor(100.0f, "trace", combinedAudioFrameWriter, 8192,
                500); // 修正构造函数参数

            // 启动 DeepFilterNetStreamProcessor 的内部处理线程
            streamProcessor.start();

            int frameLength = streamProcessor.getFrameLength();
            int bytesPerFrame = DeepFilterNetProcessingAgent.AUDIO_FORMAT.getFrameSize();
            // byte[] buffer = new byte[frameLength * bytesPerFrame]; // 改为 ByteBuffer
            ByteBuffer buffer = ByteBuffer.allocateDirect(frameLength * bytesPerFrame); // 使用 DirectByteBuffer 优化性能
            buffer.order(DeepFilterNetProcessingAgent.AUDIO_FORMAT.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

            // 新增：用于从 TargetDataLine 读取数据的临时 byte[] 缓冲区
            byte[] tempReadBuffer = new byte[frameLength * bytesPerFrame];

            log.info("正在启动麦克风捕获和降噪处理，按 Ctrl+C 停止...");

            TargetDataLine targetDataLine;
            DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, DeepFilterNetProcessingAgent.AUDIO_FORMAT);
            if (!AudioSystem.isLineSupported(targetInfo)) {
                log.error("麦克风输入不支持此音频格式: {}.", DeepFilterNetProcessingAgent.AUDIO_FORMAT);
                throw new LineUnavailableException("麦克风输入不支持此音频格式。");
            }
            targetDataLine = (TargetDataLine)AudioSystem.getLine(targetInfo);
            targetDataLine.open(DeepFilterNetProcessingAgent.AUDIO_FORMAT, frameLength * DeepFilterNetProcessingAgent.AUDIO_FORMAT.getFrameSize() * 4); // 缓冲区大小
            targetDataLine.start();

            // 麦克风捕获循环现在使用虚拟线程
            TargetDataLine finalTargetDataLine = targetDataLine;
            DeepFilterNetStreamProcessor finalStreamProcessor = streamProcessor;
            CombinedAudioFrameWriter finalCombinedAudioFrameWriter = combinedAudioFrameWriter;

            Thread captureVirtualThread = Thread.ofVirtual().name("MicCaptureVirtualThread").start(() -> {
                try {
                    while (finalStreamProcessor.isRunning()) { // 持续捕获，直到 DeepFilterNetStreamProcessor 停止
                        // int bytesRead = finalTargetDataLine.read(buffer, 0, buffer.length);
                        buffer.clear(); // 准备写入
                        // 将数据读取到临时的 byte[] 缓冲区
                        int bytesRead = finalTargetDataLine.read(tempReadBuffer, 0, tempReadBuffer.length);
                        // 将数据从临时的 byte[] 放入 DirectByteBuffer
                        buffer.put(tempReadBuffer, 0, bytesRead);
                        buffer.limit(bytesRead); // 设置 limit 为实际读取的字节数
                        if (bytesRead == buffer.capacity()) { // 只处理完整帧
                            // byte[] bufferCopy = java.util.Arrays.copyOf(buffer, bytesRead);
                            buffer.rewind(); // 重置 position 到 0，准备读取

                            // 为 onOriginalAudioFrame 创建一个 ByteBuffer 副本，确保独立操作
                            ByteBuffer originalBuffer = buffer.duplicate();
                            finalCombinedAudioFrameWriter.onOriginalAudioFrame(originalBuffer); // 写入原始音频

                            // 为 processAudioFrame 创建一个 ByteBuffer 副本，确保独立操作
                            ByteBuffer processingBuffer = buffer.duplicate();
                            finalStreamProcessor.processAudioFrame(processingBuffer); // 降噪处理
                        } else if (bytesRead == -1) {
                            break;
                        } else if (bytesRead > 0) {
                            // 捕获到不完整的帧，暂时不处理
                        }
                    }
                } catch (Exception e) {
                    log.error("麦克风捕获虚拟线程发生错误: {}", e.getMessage(), e);
                } finally {
                    finalTargetDataLine.stop();
                    finalTargetDataLine.close();
                    log.info("TargetDataLine 已停止并关闭。");
                }
            });

            // 主线程等待 streamProcessor 完成处理，而不是直接等待捕获循环
            while (streamProcessor.isRunning()) {
                try {
                    Thread.sleep(100); // 短暂休眠，等待处理完成
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("主线程在等待 DeepFilterNetStreamProcessor 完成处理时被中断: {}", e.getMessage());
                    break;
                }
            }
            // 通知捕获虚拟线程停止（如果它还在运行）
            captureVirtualThread.interrupt();
            try {
                captureVirtualThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待麦克风捕获虚拟线程结束时被中断: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("麦克风实时降噪应用发生错误: {}", e.getMessage(), e);
        } finally {
            if (streamProcessor != null) {
                streamProcessor.stop(); // 确保释放 DeepFilterNetStreamProcessor 资源
            }
            try {
                if (combinedAudioFrameWriter != null) {
                    combinedAudioFrameWriter.close();
                }
            } catch (Exception e) {
                log.error("关闭 CombinedAudioFrameWriter 失败: {}", e.getMessage(), e);
            }
        }
    }

}
