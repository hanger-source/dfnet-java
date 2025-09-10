package source.hanger;

import javax.sound.sampled.AudioFormat;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/*
 * `WavFileWriter` 是一个包级私有（package-private）的工具类，用于处理 WAV 文件的写入。
 * 它封装了 WAV 文件头生成和更新的逻辑，以及音频数据的写入。
 */
class WavFileWriter implements AutoCloseable, java.io.Flushable {
    private final File outputFile;
    private final OutputStream os;
    private final AudioFormat format;
    private long bytesWritten = 0;

    public WavFileWriter(AudioFormat format, String outputFilePath) throws IOException {
        this.outputFile = new File(outputFilePath);
        this.format = format;

        // 确保输出目录存在
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

        this.os = new java.io.FileOutputStream(outputFile); // 创建实际的FileOutputStream
        // 写入 WAV 文件头，稍后会更新数据大小
        writeWavHeader(os, format, 0); // 暂时写入0，后续需要更新
        System.out.println("DF_LOG: WAV 文件写入器已创建，文件路径: " + outputFilePath);
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
        System.out.println("DF_LOG: WAV 文件写入器已关闭，文件路径: " + outputFile.getAbsolutePath() + ", 写入字节数: " + bytesWritten);
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
