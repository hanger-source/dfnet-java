package source.hanger.demo;

import java.io.IOException;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;

import lombok.extern.slf4j.Slf4j;
import source.hanger.util.AudioFrameListener;
import source.hanger.util.WavFileWriter;

@Slf4j
public class CombinedAudioFrameWriter implements AudioFrameListener, AutoCloseable {
    private final WavFileWriter originalWavWriter;
    private final WavFileWriter denoisedWavWriter;

    public CombinedAudioFrameWriter(AudioFormat format, String originalFilePath, String denoisedFilePath) throws IOException {
        this.originalWavWriter = new WavFileWriter(format, originalFilePath);
        this.denoisedWavWriter = new WavFileWriter(format, denoisedFilePath);
    }

    @Override
    public void onOriginalAudioFrame(byte[] audioBytes, int offset, int length) {
        // 将 byte[] 转换为 ByteBuffer，然后调用接收 ByteBuffer 的方法
        ByteBuffer buffer = ByteBuffer.wrap(audioBytes, offset, length);
        onOriginalAudioFrame(buffer);
    }

    public void onOriginalAudioFrame(ByteBuffer audioBuffer) {
        try {
            originalWavWriter.write(audioBuffer);
        } catch (IOException e) {
            log.error("DF_ERROR: 写入原始音频文件失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public void onDenoisedAudioFrame(byte[] audioBytes, int offset, int length) {
        // 将 byte[] 转换为 ByteBuffer，然后调用接收 ByteBuffer 的方法
        ByteBuffer buffer = ByteBuffer.wrap(audioBytes, offset, length);
        onDenoisedAudioFrame(buffer);
    }

    public void onDenoisedAudioFrame(ByteBuffer audioBuffer) {
        try {
            denoisedWavWriter.write(audioBuffer);
        } catch (IOException e) {
            log.error("DF_ERROR: 写入降噪音频文件失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public void close() throws Exception {
        try {
            originalWavWriter.close();
        } catch (Exception e) {
            log.error("DF_ERROR: 关闭原始 WAV 文件写入器失败: {}", e.getMessage(), e);
        }
        try {
            denoisedWavWriter.close();
        } catch (Exception e) {
            log.error("DF_ERROR: 关闭降噪 WAV 文件写入器失败: {}", e.getMessage(), e);
        }
    }
}
