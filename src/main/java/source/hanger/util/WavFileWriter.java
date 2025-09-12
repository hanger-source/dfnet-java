package source.hanger.util;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import javax.sound.sampled.AudioFormat;

import lombok.extern.slf4j.Slf4j;

/*
 * `WavFileWriter` 是一个包级私有（package-private）的工具类，用于处理 WAV 文件的写入。
 * 它封装了 WAV 文件头生成和更新的逻辑，以及音频数据的写入。
 */
@Slf4j
public class WavFileWriter implements AutoCloseable, java.io.Flushable, AudioFrameListener {
    private final File outputFile;
    private final OutputStream os;
    private long bytesWritten = 0;

    public WavFileWriter(AudioFormat format, String filePath) throws IOException {
        this.outputFile = new File(filePath);

        // 确保输出目录存在
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

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
            raf.writeInt(Integer.reverseBytes((int)(totalFileSize - 8))); // RIFF Chunk Size
            raf.seek(40); // 跳到 data sub-chunk size 字段
            raf.writeInt(Integer.reverseBytes((int)totalAudioDataLength)); // Data Chunk Size
        } catch (Exception e) {
            log.error("更新 WAV 文件头失败: {}", e.getMessage(), e);
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

    /**
     * 将 ByteBuffer 的内容写入 WAV 文件。
     *
     * @param buffer 包含音频数据的 ByteBuffer
     * @throws IOException 如果写入失败
     */
    public void write(ByteBuffer buffer) throws IOException {
        int len = buffer.remaining();
        // 直接从 ByteBuffer 写入 OutputStream
        // 注意：这里需要确保 buffer 的 position 和 limit 是正确的
        // 如果是 DirectByteBuffer，可以直接包装成 InputStream 或使用 Channel
        // 但为了简化，这里直接将其内容复制到 byte[] 中再写入，可能会有一次复制
        // 更优的做法是使用 FileChannel.write(ByteBuffer) 如果 OutputStream 支持
        // 但 OutputStream 默认不支持 ByteBuffer，所以这里做一次复制是常见的妥协
        byte[] tempBytes = new byte[len];
        buffer.get(tempBytes);
        os.write(tempBytes, 0, len);
        bytesWritten += len;
    }

    @Override
    public void onDenoisedAudioFrame(byte[] audioBytes, int offset, int length) {
        try {
            write(audioBytes, offset, length);
        } catch (IOException e) {
            log.error("写入降噪音频帧失败: {}", e.getMessage(), e);
        }
    }

    // 写入 WAV 文件头，数据长度暂时为0
    private void writeWavHeader(OutputStream out, AudioFormat format, long totalAudioLen) throws IOException {
        int channels = format.getChannels();
        int sampleRate = (int)format.getSampleRate();
        int bitsPerSample = format.getSampleSizeInBits();
        int bytesPerSample = bitsPerSample / 8;
        long byteRate = (long)sampleRate * channels * bytesPerSample;
        long totalDataLen = totalAudioLen;
        long totalFileSize = 36 + totalDataLen; // 36 bytes for header + data

        byte[] header = new byte[44];

        header[0] = 'R'; // RIFF/WAVE header
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';

        header[4] = (byte)(totalFileSize & 0xff); // Chunk Size
        header[5] = (byte)((totalFileSize >> 8) & 0xff);
        header[6] = (byte)((totalFileSize >> 16) & 0xff);
        header[7] = (byte)((totalFileSize >> 24) & 0xff);

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

        header[22] = (byte)channels; // channels
        header[23] = 0;

        header[24] = (byte)(sampleRate & 0xff); // Sample Rate
        header[25] = (byte)((sampleRate >> 8) & 0xff);
        header[26] = (byte)((sampleRate >> 16) & 0xff);
        header[27] = (byte)((sampleRate >> 24) & 0xff);

        header[28] = (byte)(byteRate & 0xff); // Byte Rate
        header[29] = (byte)((byteRate >> 8) & 0xff);
        header[30] = (byte)((byteRate >> 16) & 0xff); // 修复：重新添加被删除的行
        header[31] = (byte)((byteRate >> 24) & 0xff);

        header[32] = (byte)(channels * bytesPerSample); // Block Align
        header[33] = 0;

        header[34] = (byte)bitsPerSample; // Bits per sample
        header[35] = 0;

        header[36] = 'd'; // "data" chunk
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';

        header[40] = (byte)(totalAudioLen & 0xff); // Data Chunk Size
        header[41] = (byte)((totalAudioLen >> 8) & 0xff);
        header[42] = (byte)((totalAudioLen >> 16) & 0xff);
        header[43] = (byte)((totalAudioLen >> 24) & 0xff);

        out.write(header, 0, 44);
    }

}
