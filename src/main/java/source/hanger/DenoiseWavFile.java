package source.hanger;

import java.io.File;

public class DenoiseWavFile {

    // 相对路径：模型文件现在位于 dfnet-java 项目的 models 目录下
    private static final String MODEL_RELATIVE_PATH = "models/DeepFilterNet3_onnx.tar.gz";
    // 相对路径：输入 WAV 文件现在位于 dfnet-java 项目的 data 目录下
    private static final String INPUT_WAV_RELATIVE_PATH = "data/speech_with_noise_48k.wav";
    private static final String OUTPUT_DIR = "out/"; // 输出目录位于 dfnet-java 项目根目录下的 out 目录
    private static final String OUTPUT_WAV_NAME = "speech_with_noise_48k_by_java.wav";

    public static void main(String[] args) {
        // JNA 库路径现在由 DeepFilterNetNativeLib 的静态初始化块负责设置，此处不再需要。
        // String osName = System.getProperty("os.name").toLowerCase();
        // String osArch = System.getProperty("os.arch").toLowerCase();
        // String nativeLibPath = "lib/" + osName + "-" + osArch;
        // System.setProperty("jna.library.path", nativeLibPath);

        try {
            // 获取项目根目录，用于构建模型和输入文件的绝对路径
            String projectRoot = new File(".").getAbsolutePath();
            projectRoot = projectRoot.substring(0, projectRoot.length() - (projectRoot.endsWith(".") ? 1 : 0)); // 移除末尾可能存在的.
            if (!projectRoot.endsWith(File.separator)) {
                projectRoot += File.separator;
            }
            
            String modelAbsolutePath = projectRoot + MODEL_RELATIVE_PATH;
            String inputWavAbsolutePath = projectRoot + INPUT_WAV_RELATIVE_PATH;

            // 确保输出目录存在
            File outputDir = new File(OUTPUT_DIR);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            String outputWavPath = new File(outputDir, OUTPUT_WAV_NAME).getAbsolutePath();

            DeepFilterNetProcessor processor = new DeepFilterNetProcessor(modelAbsolutePath, 100.0f, "info");
            processor.denoiseWavFile(inputWavAbsolutePath, outputWavPath);
            processor.release(); // 确保释放资源
        } catch (Exception e) {
            System.err.println("DF_LOG_ERROR: 应用程序执行失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
