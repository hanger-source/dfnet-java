package source.hanger.processor;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import com.sun.jna.Pointer;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.SleepingIdleStrategy;
import source.hanger.jna.DeepFilterNetLibraryInitializer;
import source.hanger.jna.DeepFilterNetNativeLib;
import source.hanger.log.DfNativeLogAgent;
import source.hanger.util.WavFileWriter;

@Slf4j
public class DeepFilterNetProcessor {

    private final DeepFilterNetNativeLib nativeLib;
    private final int frameLength;
    private final AgentRunner logAgentRunner;
    private Pointer dfState;

    /**
     * DeepFilterNetProcessor 构造函数，初始化模型。
     *
     * @param modelPath DeepFilterNet ONNX 模型文件路径 (.tar.gz)。
     * @param attenLim  衰减限制 (dB)。
     * @param logLevel  日志级别 (例如 "info", "debug")。
     * @throws RuntimeException 如果模型无法创建或文件不存在。
     */
    public DeepFilterNetProcessor(String modelPath, float attenLim, String logLevel) throws RuntimeException {
        // 确保模型文件存在
        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            throw new RuntimeException("DF_ERROR: 模型文件不存在: " + modelPath);
        }

        // 获取 DeepFilterNetNativeLib 实例
        nativeLib = DeepFilterNetLibraryInitializer.getNativeLibraryInstance();

        // 1. 创建 DeepFilterNet 模型实例
        dfState = nativeLib.df_create(modelPath, attenLim, logLevel);
        if (dfState == Pointer.NULL) {
            throw new RuntimeException("DF_ERROR: 无法创建 DeepFilterNet 模型。请检查模型路径或日志。");
        }
        log.info("DF_LOG: DeepFilterNet 模型创建成功。");

        // 启动日志代理
        DfNativeLogAgent logAgent = new DfNativeLogAgent(dfState);
        logAgentRunner = new AgentRunner(new SleepingIdleStrategy(100_000), // 100微秒 = 0.1毫秒
            exception -> log.error("DF_LOG_ERROR: 日志代理出现异常: {}", exception.getMessage(), exception), null, logAgent);
        AgentRunner.startOnThread(logAgentRunner);

        // 2. 获取 DeepFilterNet 期望的帧长度
        frameLength = nativeLib.df_get_frame_length(dfState);
        log.info("DF_LOG: DeepFilterNet 期望的帧长度 (样本数): {}", frameLength);
    }

    /**
     * 处理 WAV 文件并生成降噪后的文件。
     *
     * @param inputWavPath  输入 WAV 文件路径。
     * @param outputWavPath 输出降噪后的 WAV 文件路径。
     * @throws IOException 如果文件操作失败。
     * @throws UnsupportedAudioFileException 如果输入文件不是有效的 WAV 格式。
     * @throws RuntimeException 如果音频处理过程中发生错误。
     */
    public void denoiseWavFile(String inputWavPath, String outputWavPath)
        throws IOException, UnsupportedAudioFileException, RuntimeException {
        File inputFile = new File(inputWavPath);
        if (!inputFile.exists()) {
            throw new IOException("DF_ERROR: 输入WAV文件不存在: " + inputWavPath);
        }

        try (AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(inputFile)) {
            AudioFormat audioFormat = audioInputStream.getFormat();
            log.info("DF_LOG: 输入音频格式: {}", audioFormat.toString());

            if (audioFormat.getChannels() != 1) {
                throw new UnsupportedAudioFileException(
                    "DF_ERROR: DeepFilterNet 仅支持单声道音频。输入文件有 " + audioFormat.getChannels() + " 声道。");
            }
            if (audioFormat.getSampleSizeInBits() != 16) {
                log.warn(
                    "DF_WARNING: 建议使用 16 bit 音频。输入文件是 {} bit。", audioFormat.getSampleSizeInBits());
            }
            if (audioFormat.getSampleRate() != 48000.0f) {
                log.warn(
                    "DF_WARNING: 建议使用 48kHz 采样率。输入文件是 {} Hz。", audioFormat.getSampleRate());
            }

            // 创建 WavFileWriter，用于写入降噪后的数据
            try (WavFileWriter outputWriter = new WavFileWriter(audioFormat, outputWavPath)) {
                byte[] audioBytes = new byte[frameLength * audioFormat.getFrameSize()];
                float[] inputFloats = new float[frameLength];
                float[] outputFloats = new float[frameLength];
                ByteBuffer byteBuffer = ByteBuffer.allocate(frameLength * 4); // 4 bytes per float
                byteBuffer.order(audioFormat.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

                int bytesRead;
                int frameCount = 0;
                log.info("DF_LOG: 开始处理音频帧...");

                while ((bytesRead = audioInputStream.read(audioBytes)) != -1) {
                    if (bytesRead < audioBytes.length) {
                        // 处理最后一帧可能不足 frameLength 的情况
                        // 为了简化，这里直接跳过不足一帧的数据
                        // 更完善的处理方式可以是填充0或复制最后一点数据
                        log.warn("DF_WARNING: 最后一帧不足 {} 样本，已忽略。", frameLength);
                        break;
                    }

                    // 将 byte[] 转换为 float[]
                    byteBuffer.clear();
                    byteBuffer.put(audioBytes);
                    byteBuffer.flip();
                    for (int i = 0; i < frameLength; i++) {
                        inputFloats[i] = byteBuffer.getShort(i * 2) / 32768.0f; // 16-bit PCM to float
                    }

                    // 处理音频帧
                    nativeLib.df_process_frame(dfState, inputFloats, outputFloats);

                    // 将 float[] 转换为 byte[] (16-bit PCM)
                    byteBuffer.clear();
                    for (int i = 0; i < frameLength; i++) {
                        short s = (short)(outputFloats[i] * 32768.0f);
                        byteBuffer.putShort(s);
                    }
                    outputWriter.write(byteBuffer.array(), 0, bytesRead); // 写入原始字节数

                    frameCount++;
                }
                log.info("DF_LOG: 音频处理完成。处理了 {} 帧。", frameCount);
                log.info("DF_LOG: 降噪后的 WAV 文件已保存到: {}", outputWavPath);
            }

        }
    }

    /**
     * 释放 DeepFilterNet 模型资源。
     */
    public void release() {
        if (logAgentRunner != null) {
            logAgentRunner.close();
            log.info("DF_LOG_INFO: 日志代理已关闭。");
        }
        if (dfState != Pointer.NULL) {
            nativeLib.df_free(dfState);
            dfState = Pointer.NULL;
            log.info("DF_LOG: DeepFilterNet 模型资源已释放。");
        }
    }
}
