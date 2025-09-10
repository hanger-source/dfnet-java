package source.hanger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

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

        DeepFilterNetStreamProcessor processor = null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        try {
            // 1. 初始化 DeepFilterNetStreamProcessor
            processor = new DeepFilterNetStreamProcessor(modelPath, attenLim, logLevel, audioFormat);

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
        }
    }
}
