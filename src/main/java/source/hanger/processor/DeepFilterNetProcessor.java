package source.hanger.processor;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import com.sun.jna.Pointer;
import lombok.extern.slf4j.Slf4j;
import source.hanger.DeepFilterNetServiceInitializer;
import source.hanger.jna.DeepFilterNetLibraryInitializer;
import source.hanger.jna.DeepFilterNetNativeLib;
import source.hanger.model.DeepFilterNetModelManager;
import source.hanger.processor.agent.DeepFilterNetListenerAgent;
import source.hanger.processor.agent.ProcessorOutputGroup;
import source.hanger.util.AudioFrameListener;
import source.hanger.util.WavFileWriter;

@Slf4j
public class DeepFilterNetProcessor {

    private static final long AGENT_SHUTDOWN_TIMEOUT_MS = 500;

    private final DeepFilterNetNativeLib nativeLib;
    private final int frameLength;
    private Pointer dfState;
    private final String processorId; // 新增：用于唯一标识处理器实例

    /**
     * DeepFilterNetProcessor 构造函数，初始化模型。
     *
     * @param attenLim 衰减限制 (dB)。
     * // 移除 logLevel 参数
     * @throws RuntimeException 如果模型无法创建或文件不存在。
     */
    public DeepFilterNetProcessor(float attenLim) throws RuntimeException {
        // 确保 DeepFilterNet 核心服务已初始化 (包括 DeepFilterNetListenerAgent 的 AgentRunner)
        DeepFilterNetServiceInitializer.initialize();

        nativeLib = DeepFilterNetLibraryInitializer.getNativeLibraryInstance();
        this.processorId = java.util.UUID.randomUUID().toString(); // 生成唯一 ID

        dfState = nativeLib.df_create(DeepFilterNetModelManager.getModelPath(), attenLim, null); // 传递 null 禁用原生日志
        if (dfState == Pointer.NULL) {
            throw new RuntimeException("DF_ERROR: 无法创建 DeepFilterNet 模型。请检查模型路径或日志。");
        }
        log.info("DF_LOG: DeepFilterNet 模型创建成功。");

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

            try (WavFileWriter outputWriter = new WavFileWriter(audioFormat, outputWavPath)) {
                byte[] audioBytes = new byte[frameLength * audioFormat.getFrameSize()];
                float[] inputFloats = new float[frameLength];
                float[] outputFloats = new float[frameLength];
                ByteBuffer byteBuffer = ByteBuffer.allocate(frameLength * 4);
                byteBuffer.order(audioFormat.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

                int bytesRead;
                int frameCount = 0;
                log.info("DF_LOG: 开始处理音频帧...");

                while ((bytesRead = audioInputStream.read(audioBytes)) != -1) {
                    if (bytesRead < audioBytes.length) {
                        log.warn("DF_WARNING: 最后一帧不足 {} 样本，已忽略。", frameLength);
                        break;
                    }

                    byteBuffer.clear();
                    byteBuffer.put(audioBytes);
                    byteBuffer.flip();
                    for (int i = 0; i < frameLength; i++) {
                        inputFloats[i] = byteBuffer.getShort(i * 2) / 32768.0f;
                    }

                    nativeLib.df_process_frame(dfState, inputFloats, outputFloats);

                    byteBuffer.clear();
                    for (int i = 0; i < frameLength; i++) {
                        short s = (short)(outputFloats[i] * 32768.0f);
                        byteBuffer.putShort(s);
                    }
                    outputWriter.write(byteBuffer.array(), 0, bytesRead);

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
        if (dfState != Pointer.NULL) {
            nativeLib.df_free(dfState);
            dfState = Pointer.NULL;
            log.info("DF_LOG: DeepFilterNet 模型资源已释放。");
        }
    }
}
