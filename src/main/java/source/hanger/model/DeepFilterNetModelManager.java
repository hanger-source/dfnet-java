package source.hanger.model;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@Slf4j
public class DeepFilterNetModelManager {

    private static final String MODEL_RESOURCE_PATH = "models/DeepFilterNet3_onnx.tar.gz"; // JAR 内部模型路径
    private static File modelTempFile; // 用于存储已提取的临时模型文件的引用

    /**
     * 获取 DeepFilterNet ONNX 模型文件的绝对路径。
     * 模型文件将从 JAR 资源中提取到临时位置，并确保在应用程序生命周期中只被提取一次。
     * 提取的临时文件将在 JVM 退出时自动删除。
     *
     * @return DeepFilterNet ONNX 模型文件的绝对路径。
     * @throws UncheckedIOException 如果无法提取模型资源。
     */
    public static synchronized String getModelPath() {
        if (modelTempFile == null) {
            try (InputStream inputStream = DeepFilterNetModelManager.class.getClassLoader().getResourceAsStream(
                    MODEL_RESOURCE_PATH)) {
                if (inputStream == null) {
                    log.error("DF_ERROR: JAR 资源中未找到模型文件: {}", MODEL_RESOURCE_PATH);
                    throw new FileNotFoundException("JAR 资源中未找到模型文件: " + MODEL_RESOURCE_PATH);
                }
                modelTempFile = File.createTempFile("df_model_", ".tar.gz");
                modelTempFile.deleteOnExit(); // 确保 JVM 退出时删除文件
                Files.copy(inputStream, modelTempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("DF_INFO: 模型已提取到临时文件: {}", modelTempFile.getAbsolutePath());
            } catch (IOException e) {
                log.error("DF_ERROR: 无法提取模型资源: {}", e.getMessage());
                throw new UncheckedIOException("无法提取模型资源", e);
            }
        }
        return modelTempFile.getAbsolutePath();
    }
}
