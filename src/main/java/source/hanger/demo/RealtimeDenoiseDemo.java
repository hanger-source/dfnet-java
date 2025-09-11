package source.hanger.demo;

import java.io.File;
import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import lombok.extern.slf4j.Slf4j;
import source.hanger.processor.DeepFilterNetStreamProcessor;

@Slf4j
public class RealtimeDenoiseDemo {

    public static void main(String[] args) {
        String modelPath = "models/DeepFilterNet3_onnx.tar.gz";
        String inputWavPath = "data/speech_with_noise_48k.wav";
        String outputOriginalWavPath = "out/original_audio.wav";
        String outputDenoisedWavPath = "out/denoised_audio.wav";

        AudioInputStream audioInputStream = null;
        DeepFilterNetStreamProcessor streamProcessor = null;
        CombinedAudioFrameWriter combinedAudioFrameWriter = null; // 使用 CombinedAudioFrameWriter

        try {
            audioInputStream = AudioSystem.getAudioInputStream(new File(inputWavPath));
            AudioFormat format = audioInputStream.getFormat();

            if (format.getChannels() != 1 || format.getSampleRate() != 48000.0f || format.getSampleSizeInBits() != 16) {
                throw new UnsupportedAudioFileException("输入WAV文件必须是单声道、48kHz、16bit PCM 格式。");
            }

            combinedAudioFrameWriter = new CombinedAudioFrameWriter(format, outputOriginalWavPath, outputDenoisedWavPath);
            streamProcessor = new DeepFilterNetStreamProcessor(format, modelPath, 100.0f, "trace", combinedAudioFrameWriter, 8192, 500); // 修正构造函数参数
            log.info("DF_DIAG: RealtimeDenoiseDemo: streamProcessor 实例化完成。");

            byte[] buffer = new byte[4096]; // 使用通用缓冲区大小，例如 4096 字节

            // 启动 DeepFilterNetStreamProcessor 的内部处理线程
            streamProcessor.start();
            log.info("DF_DIAG: RealtimeDenoiseDemo: streamProcessor.start() 调用完成。初始 isRunning(): {}", streamProcessor.isRunning());

            int bytesRead;
            int totalBytesRead = 0;
            while ((bytesRead = audioInputStream.read(buffer)) != -1) {
                totalBytesRead += bytesRead;
                log.debug("DF_DIAG: RealtimeDenoiseDemo: 读取音频数据。bytesRead: {}, totalBytesRead: {}", bytesRead, totalBytesRead);
                byte[] bufferCopy = java.util.Arrays.copyOf(buffer, bytesRead);
                combinedAudioFrameWriter.onOriginalAudioFrame(bufferCopy, 0, bytesRead); // 写入原始数据
                streamProcessor.processAudioFrame(bufferCopy);
            }
            log.info("DF_DIAG: RealtimeDenoiseDemo: 文件读取循环结束。总共读取字节数: {}", totalBytesRead);

            // 通知 DeepFilterNetStreamProcessor 输入已结束
            log.info("DF_DIAG: RealtimeDenoiseDemo: 调用 streamProcessor.signalEndOfInput()。");
            streamProcessor.signalEndOfInput();
            log.info("DF_DIAG: RealtimeDenoiseDemo: signalEndOfInput() 调用完成。当前 isRunning(): {}", streamProcessor.isRunning());

            // 等待处理器完成所有剩余数据的处理
            long startTime = System.currentTimeMillis();
            while (streamProcessor.isRunning()) {
                log.debug("DF_DIAG: RealtimeDenoiseDemo: 等待 streamProcessor 完成处理。当前 isRunning(): {}", streamProcessor.isRunning());
                Thread.sleep(100); // 避免忙等
                if (System.currentTimeMillis() - startTime > 10000) { // 增加超时机制
                    log.warn("DF_WARN: RealtimeDenoiseDemo: 等待 streamProcessor 完成处理超时 (10秒)。强制停止。");
                    break;
                }
            }
            log.info("DF_DIAG: RealtimeDenoiseDemo: streamProcessor.isRunning() 为 false。");


            log.info("DF_DIAG: RealtimeDenoiseDemo: 调用 streamProcessor.stop() 来优雅关闭处理器。");
            // streamProcessor.stop(); // 移除此处对 stop() 的调用
            log.info("DF_DIAG: RealtimeDenoiseDemo: streamProcessor.stop() 调用完成。");

            log.info("DeepFilterNetStreamProcessor 已完成所有音频帧的处理。");

        } catch (Exception e) { // Simplified exception handling
            log.error("DF_ERROR: 实时降噪应用发生错误: {}", e.getMessage(), e);
        } finally {
            if (audioInputStream != null) {
                try {
                    audioInputStream.close();
                } catch (IOException e) {
                    log.error("DF_ERROR: 关闭音频输入流失败: {}", e.getMessage(), e);
                }
            }
            if (combinedAudioFrameWriter != null) {
                try {
                    combinedAudioFrameWriter.close();
                } catch (Exception e) {
                    log.error("DF_ERROR: 关闭 CombinedAudioFrameWriter 失败: {}", e.getMessage(), e);
                }
            }
            if (streamProcessor != null) {
                log.info("DF_DIAG: RealtimeDenoiseDemo: 在 finally 块中调用 streamProcessor.stop()。");
                streamProcessor.stop(); // 移到 finally 块确保关闭
            }
        }
    }
}
