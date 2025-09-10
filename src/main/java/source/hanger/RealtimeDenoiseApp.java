package source.hanger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.File;

public class RealtimeDenoiseApp {

    public static void main(String[] args) {
        // DeepFilterNet 模型路径 (相对于项目根目录)
        String modelPath = "models/DeepFilterNet3_onnx.tar.gz";
        float attenLim = 100.0f; // 衰减限制 (dB)
        String logLevel = "info"; // 日志级别

        // DeepFilterNet 要求的音频格式
        AudioFormat audioFormat = new AudioFormat(
                48000.0f, // 采样率
                16,       // 采样位数
                1,        // 声道数 (单声道)
                true,     // 有符号
                false     // 小端字节序 (与 dfnet-java 中的 ByteBuffer 保持一致)
        );

        // 定义输出文件路径
        String originalOutputFilePath = "out" + File.separator + "original_audio.wav";
        String denoisedOutputFilePath = "out" + File.separator + "denoised_audio.wav";

        DeepFilterNetStreamProcessor processor = null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        RealtimeAudioWriter audioWriter = null; // 用于写入音频文件的监听器

        try {
            // 创建 RealtimeAudioWriter 实例
            audioWriter = new RealtimeAudioWriter(audioFormat, originalOutputFilePath, denoisedOutputFilePath);

            // 1. 初始化 DeepFilterNetStreamProcessor，并传入音频写入监听器
            processor = new DeepFilterNetStreamProcessor(modelPath, attenLim, logLevel, audioFormat, audioWriter);

            // 2. 启动实时降噪处理
            processor.start();

            System.out.println("DeepFilterNet 实时降噪已启动。按下回车键停止...");
            reader.readLine(); // 等待用户按下回车

            // 3. 停止实时降噪处理
            processor.stop();

        } catch (LineUnavailableException e) {
            System.err.println("DF_APP_ERROR: 音频线路不可用: " + e.getMessage());
            e.printStackTrace();
        } catch (IOException e) {
            System.err.println("DF_APP_ERROR: 读取输入时发生错误: " + e.getMessage());
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            System.err.println("DF_APP_ERROR: 无效的参数或不支持的音频格式: " + e.getMessage());
            e.printStackTrace();
        } catch (RuntimeException e) {
            System.err.println("DF_APP_ERROR: DeepFilterNet 处理失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (processor != null) {
                // Ensure resources are released if an error occurs before stop() is called
                processor.release(); // stop() already calls release(), but good practice for robustness
            }
            if (audioWriter != null) {
                try {
                    audioWriter.close(); // 确保关闭 WAV 文件写入器
                } catch (IOException e) {
                    System.err.println("DF_APP_ERROR: 关闭 WAV 文件写入器时发生错误: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 内部类 RealtimeAudioWriter 实现 AudioFrameListener 接口，负责将音频帧写入 WAV 文件。
     */
    private static class RealtimeAudioWriter implements AudioFrameListener, AutoCloseable {
        private final WavFileWriter originalWavWriter;
        private final WavFileWriter denoisedWavWriter;

        public RealtimeAudioWriter(AudioFormat format, String originalOutputFilePath, String denoisedOutputFilePath) throws IOException {
            this.originalWavWriter = new WavFileWriter(format, originalOutputFilePath);
            this.denoisedWavWriter = new WavFileWriter(format, denoisedOutputFilePath);
        }

        @Override
        public void onOriginalAudioFrame(byte[] audioBytes, int offset, int length) {
            try {
                originalWavWriter.write(audioBytes, offset, length);
                System.out.println(String.format("DF_TRACE: RealtimeAudioWriter: original frame written, bytes: %d, first 4 bytes: %02X %02X %02X %02X", length, audioBytes[offset], audioBytes[offset+1], audioBytes[offset+2], audioBytes[offset+3]));
            } catch (IOException e) {
                System.err.println("DF_APP_ERROR: 写入原始音频文件失败: " + e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public void onDenoisedAudioFrame(byte[] audioBytes, int offset, int length) {
            try {
                denoisedWavWriter.write(audioBytes, offset, length);
                System.out.println(String.format("DF_TRACE: RealtimeAudioWriter: denoised frame written, bytes: %d, first 4 bytes: %02X %02X %02X %02X", length, audioBytes[offset], audioBytes[offset+1], audioBytes[offset+2], audioBytes[offset+3]));
            } catch (IOException e) {
                System.err.println("DF_APP_ERROR: 写入降噪后音频文件失败: " + e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public void close() throws IOException {
            originalWavWriter.close();
            denoisedWavWriter.close();
        }
    }
}
