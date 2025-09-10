package source.hanger;

import com.sun.jna.Pointer;
import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.OutputStream;

public class DeepFilterNetProcessor {

    private DeepFilterNetNativeLib nativeLib;
    private Pointer dfState;
    private int frameLength;
    private AudioFormat audioFormat;

    private DfNativeLogThread logThread;

    /**
     * DeepFilterNetProcessor 构造函数，初始化模型。
     * @param modelPath DeepFilterNet ONNX 模型文件路径 (.tar.gz)。
     * @param attenLim 衰减限制 (dB)。
     * @param logLevel 日志级别 (例如 "info", "debug")。
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
        System.out.println("DF_LOG: DeepFilterNet 模型创建成功。");

        // 启动日志线程
        logThread = new DfNativeLogThread(dfState);
        logThread.start();

        // 2. 获取 DeepFilterNet 期望的帧长度
        frameLength = nativeLib.df_get_frame_length(dfState);
        System.out.println("DF_LOG: DeepFilterNet 期望的帧长度 (样本数): " + frameLength);
    }

    /**
     * 处理 WAV 文件并生成降噪后的文件。
     * @param inputWavPath 输入 WAV 文件路径。
     * @param outputWavPath 输出降噪后的 WAV 文件路径。
     * @throws IOException 如果文件操作失败。
     * @throws UnsupportedAudioFileException 如果输入文件不是有效的 WAV 格式。
     * @throws RuntimeException 如果音频处理过程中发生错误。
     */
    public void denoiseWavFile(String inputWavPath, String outputWavPath) throws IOException, UnsupportedAudioFileException, RuntimeException {
        File inputFile = new File(inputWavPath);
        if (!inputFile.exists()) {
            throw new IOException("DF_ERROR: 输入WAV文件不存在: " + inputWavPath);
        }

        try (AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(inputFile)) {
            audioFormat = audioInputStream.getFormat();
            System.out.println("DF_LOG: 输入音频格式: " + audioFormat.toString());

            if (audioFormat.getChannels() != 1) {
                throw new UnsupportedAudioFileException("DF_ERROR: DeepFilterNet 仅支持单声道音频。输入文件有 " + audioFormat.getChannels() + " 声道。");
            }
            if (audioFormat.getSampleSizeInBits() != 16) {
                System.err.println("DF_WARNING: 建议使用 16 bit 音频。输入文件是 " + audioFormat.getSampleSizeInBits() + " bit。");
            }
            if (audioFormat.getSampleRate() != 48000.0f) {
                System.err.println("DF_WARNING: 建议使用 48kHz 采样率。输入文件是 " + audioFormat.getSampleRate() + " Hz。");
            }

            // 确保输出目录存在
            File outputFile = new File(outputWavPath);
            File outputDir = outputFile.getParentFile();
            if (outputDir != null && !outputDir.exists()) {
                outputDir.mkdirs();
            }

            // 创建自定义 AudioOutputStream，用于写入降噪后的数据
            // 注意：这里简化了 WAV 文件头的处理。对于完整的 WAV 写入，可能需要更复杂的逻辑来处理文件头更新。
            try (AudioOutputStream outputStream = new AudioOutputStream(audioFormat, outputFile)) {
                byte[] audioBytes = new byte[frameLength * audioFormat.getFrameSize()];
                float[] inputFloats = new float[frameLength];
                float[] outputFloats = new float[frameLength];
                ByteBuffer byteBuffer = ByteBuffer.allocate(frameLength * 4); // 4 bytes per float
                byteBuffer.order(audioFormat.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

                int bytesRead;
                int frameCount = 0;
                System.out.println("DF_LOG: 开始处理音频帧...");

                while ((bytesRead = audioInputStream.read(audioBytes)) != -1) {
                    if (bytesRead < audioBytes.length) {
                        // 处理最后一帧可能不足 frameLength 的情况
                        // 为了简化，这里直接跳过不足一帧的数据
                        // 更完善的处理方式可以是填充0或复制最后一点数据
                        System.err.println("DF_WARNING: 最后一帧不足 " + frameLength + " 样本，已忽略。");
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
                        short s = (short) (outputFloats[i] * 32768.0f);
                        byteBuffer.putShort(s);
                    }
                    outputStream.write(byteBuffer.array(), 0, bytesRead); // 写入原始字节数

                    frameCount++;
                }
                System.out.println("DF_LOG: 音频处理完成。处理了 " + frameCount + " 帧。");
                System.out.println("DF_LOG: 降噪后的 WAV 文件已保存到: " + outputWavPath);
            }

        } 
        // 移除 finally 块中的 release() 调用，让调用者显式管理资源的释放
        // finally {
        //     release();
        // }
    }

    /**
     * 释放 DeepFilterNet 模型资源。
     */
    public void release() {
        if (dfState != Pointer.NULL) {
            nativeLib.df_free(dfState);
            dfState = Pointer.NULL;
            System.out.println("DF_LOG: DeepFilterNet 模型资源已释放。");
        }
        if (logThread != null) {
            logThread.stopLogging();
            try {
                logThread.join(); // 等待日志线程结束
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("DF_LOG_ERROR: 等待日志线程结束时被中断。");
            }
        }
    }

    // Custom AudioOutputStream to allow writing to a file and handling WAV headers
    // Note: This is a simplified implementation for demonstration.
    // A robust WAV file writer would need to correctly handle the WAV header update (e.g., total data size).
    private static class AudioOutputStream implements AutoCloseable, java.io.Flushable {
        private final File outputFile;
        private final OutputStream os;
        private final AudioFormat format;
        private long bytesWritten = 0;

        public AudioOutputStream(AudioFormat format, File outputFile) throws IOException {
            this.outputFile = outputFile;
            this.format = format;
            this.os = new java.io.FileOutputStream(outputFile); // 创建实际的FileOutputStream
            // 写入 WAV 文件头，稍后会更新数据大小
            writeWavHeader(os, format, 0); // 暂时写入0，后续需要更新
        }

        @Override
        public void close() throws IOException {
            os.flush();
            // 在关闭时更新 WAV 文件头
            long totalAudioDataLength = bytesWritten;
            long totalFileSize = 36 + totalAudioDataLength; // 36 bytes for header + data
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(outputFile, "rw")) {
                raf.seek(4); // 跳到 RIFF 文件大小字段
                raf.writeInt(Integer.reverseBytes((int) (totalFileSize - 8))); // RIFF Chunk Size
                raf.seek(40); // 跳到 data sub-chunk size 字段
                raf.writeInt(Integer.reverseBytes((int) totalAudioDataLength)); // Data Chunk Size
            } catch (Exception e) {
                System.err.println("DF_LOG_ERROR: 无法更新 WAV 文件头: " + e.getMessage());
            }
            os.close();
        }

        @Override
        public void flush() throws IOException {
            os.flush();
        }

        public void write(byte[] b, int off, int len) throws IOException {
            os.write(b, off, len);
            bytesWritten += len;
        }

        public void write(byte[] b) throws IOException {
            os.write(b);
            bytesWritten += b.length;
        }

        // 写入 WAV 文件头，数据长度暂时为0
        private void writeWavHeader(OutputStream out, AudioFormat format, long totalAudioLen) throws IOException {
            int channels = format.getChannels();
            int sampleRate = (int) format.getSampleRate();
            int bitsPerSample = format.getSampleSizeInBits();
            int bytesPerSample = bitsPerSample / 8;
            long byteRate = sampleRate * channels * bytesPerSample;
            long totalDataLen = totalAudioLen;
            long totalFileSize = 36 + totalDataLen; // 36 bytes for header + data

            byte[] header = new byte[44];

            header[0] = 'R'; // RIFF/WAVE header
            header[1] = 'I';
            header[2] = 'F';
            header[3] = 'F';

            header[4] = (byte) (totalFileSize & 0xff); // Chunk Size
            header[5] = (byte) ((totalFileSize >> 8) & 0xff);
            header[6] = (byte) ((totalFileSize >> 16) & 0xff);
            header[7] = (byte) ((totalFileSize >> 24) & 0xff);

            header[8] = 'W';
            header[9] = 'A';
            header[10] = 'V';
            header[11] = 'E';

            header[12] = 'f'; // 'fmt ' chunk
            header[13] = 'm';
            header[14] = 't';
            header[15] = ' ';

            header[16] = 16; // 4 bytes: size of 'fmt ' chunk
            header[17] = 0;
            header[18] = 0;
            header[19] = 0;

            header[20] = 1; // format = 1 (PCM)
            header[21] = 0;

            header[22] = (byte) channels; // channels
            header[23] = 0;

            header[24] = (byte) (sampleRate & 0xff); // Sample Rate
            header[25] = (byte) ((sampleRate >> 8) & 0xff);
            header[26] = (byte) ((sampleRate >> 16) & 0xff);
            header[27] = (byte) ((sampleRate >> 24) & 0xff);

            header[28] = (byte) (byteRate & 0xff); // Byte Rate
            header[29] = (byte) ((byteRate >> 8) & 0xff);
            header[30] = (byte) ((byteRate >> 16) & 0xff);
            header[31] = (byte) ((byteRate >> 24) & 0xff);

            header[32] = (byte) (channels * bytesPerSample); // Block Align
            header[33] = 0;

            header[34] = (byte) bitsPerSample; // Bits per sample
            header[35] = 0;

            header[36] = 'd'; // "data" chunk
            header[37] = 'a';
            header[38] = 't';
            header[39] = 'a';

            header[40] = (byte) (totalAudioLen & 0xff); // Data Chunk Size
            header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
            header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
            header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

            out.write(header, 0, 44);
        }
    }
}
