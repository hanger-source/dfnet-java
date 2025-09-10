# dfnet-java: DeepFilterNet Java JNA 封装

`dfnet-java` 提供了一个 Java 原生访问 (JNA) 封装，用于集成高性能的 [DeepFilterNet](https://github.com/Rikorose/DeepFilterNet) 降噪库。它允许 Java 应用程序以编程方式利用 DeepFilterNet 核心 Rust 库的实时音频处理能力。

## ✨ 特性

*   **基于 DeepFilterNet 核心 Rust 库：** 利用 [DeepFilterNet](https://github.com/Rikorose/DeepFilterNet) 提供的高性能音频处理。
*   **易于集成：** 作为 Maven 依赖项轻松集成到任何 Java 项目中。
*   **跨平台支持：** 支持 macOS (ARM), Linux (x64) 等多个平台，通过动态加载平台特定的本地库。
*   **简洁 API：** 提供 `DeepFilterNetProcessor` 类，封装了模型加载、音频处理和资源释放。

## 🚀 系统要求

*   **Java Development Kit (JDK):** 11 或更高版本。
*   **Apache Maven:** 3.6.0 或更高版本。
*   **Rust 编程语言环境 (用于编译 `libdf`):** 如果你需要在本地编译 `libdf` (DeepFilterNet 的 Rust 核心库)，则需要安装 Rust。
    *   推荐使用 `rustup` 进行安装：`curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh`。
    *   或者对于 macOS，可以使用 Homebrew: `brew install rust`。

## 📦 项目结构

`dfnet-java` 项目现在是独立的，模型和示例数据位于项目内部。典型的项目布局如下：

```
/dfnet-java/
├── pom.xml
├── src/
│   └── main/
│       └── java/
│           └── com/
│               └── dfnet/
│                   ├── DeepFilterNetNativeLib.java
│                   ├── DeepFilterNetProcessor.java
│                   └── DenoiseWavFile.java (示例)
├── lib/                        # 存放编译好的本地库，例如:
│   ├── macos-aarch64/
│   │   └── libdf.dylib
│   └── linux-x64/
│       └── libdf.so
├── models/                     # 存放 DeepFilterNet 模型
│   └── DeepFilterNet3_onnx.tar.gz
├── data/                       # 存放示例输入音频文件
│   └── speech_with_noise_48k.wav
└── out/                        # 示例输出音频文件目录 (由程序自动创建)
```

## ⬇️ 获取模型和示例数据

1.  **下载 DeepFilterNet ONNX 模型：**
    从 DeepFilterNet 官方仓库 (例如 [GitHub Releases](https://github.com/Rikorose/DeepFilterNet/releases)) 中找到 `DeepFilterNet3_onnx.tar.gz` 模型文件。将其下载并放置在 `dfnet-java/models/` 目录下。

2.  **获取示例输入 WAV 文件：**
    获取一个名为 `speech_with_noise_48k.wav` 的示例音频文件。将其下载并放置在 `dfnet-java/data/` 目录下。这个文件将在示例中使用。

## 🛠️ 构建 `libdf` (DeepFilterNet Rust 核心库)

`dfnet-java` 依赖于 DeepFilterNet 核心 Rust 库 (`libdf`)。你需要自行编译 `libdf` 以生成平台特定的动态链接库文件 (例如 macOS 上的 `libdf.dylib` 或 Linux 上的 `libdf.so`)。请参考 DeepFilterNet 官方仓库的指南 ([Rikorose/DeepFilterNet](https://github.com/Rikorose/DeepFilterNet))。

一旦编译完成，你需要将编译好的 `libdf` 库复制到 `dfnet-java` 项目的 `lib` 目录中对应的平台子目录：

*   **macOS (ARM):** 将 `libdf.dylib` 复制到 `dfnet-java/lib/macos-aarch64/`。
*   **Linux (x64):** 将 `libdf.so` 复制到 `dfnet-java/lib/linux-x64/`。

**示例复制命令 (假设你在 DeepFilterNet 仓库的 `target/release` 目录下)：**

*   **对于 macOS (ARM):**
    ```bash
    cp libdf.dylib /path/to/dfnet-java/lib/macos-aarch64/
    ```
*   **对于 Linux (x64):**
    ```bash
    cp libdf.so /path/to/dfnet-java/lib/linux-x64/
    ```

## 🚀 构建 `dfnet-java` (Java 项目)

1.  **进入 `dfnet-java` 项目目录：**
    ```bash
    cd dfnet-java
    ```

2.  **使用 Maven 编译和安装：**
    ```bash
    mvn clean install
    ```
    这会将 `dfnet-java` 打包为 JAR 文件并安装到你的本地 Maven 仓库中。

## 💡 使用示例

`dfnet-java` 提供了一个 `DeepFilterNetProcessor` 类，你可以通过以下方式在你的 Java 应用程序中使用它：

```java
// ... (省略导入和类定义)

public class YourApplication {
    public static void main(String[] args) {
        // 1. 设置 JNA 本地库路径
        // 确保 jna.library.path 系统属性指向你平台特定的 libdf.dylib/.so 所在的目录
        // 例如：System.setProperty("jna.library.path", "/path/to/dfnet-java/lib/macos-aarch64");
        // 或者通过 Maven 的 exec 插件配置 (详见 pom.xml)

        // 2. 定义模型和音频文件路径 (相对于你的应用程序的当前工作目录)
        String modelPath = "models/DeepFilterNet3_onnx.tar.gz";
        String inputWavPath = "data/speech_with_noise_48k.wav";
        String outputWavPath = "out/speech_with_noise_48k_denoised.wav";

        DeepFilterNetProcessor processor = null;
        try {
            // 3. 初始化 DeepFilterNetProcessor
            processor = new DeepFilterNetProcessor(modelPath, 100.0f, "info");

            // 4. 处理 WAV 文件
            processor.denoiseWavFile(inputWavPath, outputWavPath);

            System.out.println("降噪完成！输出文件: " + outputWavPath);

        } catch (Exception e) {
            System.err.println("处理过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 5. 释放资源
            if (processor != null) {
                processor.release();
            }
        }
    }
}
```

运行 `DenoiseWavFile.java` 示例：

1.  确保你已按照上述步骤编译 `libdf` 并将其复制到正确位置。
2.  确保模型文件 (`models/DeepFilterNet3_onnx.tar.gz`) 和输入 WAV 文件 (`data/speech_with_noise_48k.wav`) 位于 `dfnet-java` 项目的相应子目录。
3.  进入 `dfnet-java` 项目目录：`cd dfnet-java`
4.  运行示例：`mvn exec:java`

## ⁉️ 故障排除

*   **`java.lang.UnsatisfiedLinkError: Unable to load library 'df'`：**
    *   **原因：** JNA 无法找到 `libdf.dylib` (macOS) 或 `libdf.so` (Linux)。
    *   **解决方案：** 确保 `libdf` 已编译，并将其复制到 `dfnet-java/lib/<os>-<arch>/` 目录下。同时，检查 `pom.xml` 中 `jna.library.path` 的配置是否正确指向该目录。
*   **Rust `panic` (例如 `not yet implemented`)：**
    *   **原因：** 通常是 DeepFilterNet 模型版本与 `libdf` 所依赖的 `tract` 库版本不兼容。
    *   **解决方案：** 确保你使用的是 `DeepFilterNet3_onnx.tar.gz` 模型。如果问题仍然存在，可能需要升级 `DeepFilterNet` 官方仓库中的 `tract` 依赖并重新编译 `libdf`。

## 🤝 贡献

欢迎贡献！如果你有任何改进建议或 bug 修复，请随时提交 Pull Request。

## 📄 许可证

本项目根据 MIT 许可证和 Apache-2.0 许可证双重授权。详情请参见 `LICENSE-MIT` 和 `LICENSE-APACHE` 文件。

*   [详细构建指南和 JNA 绑定原理](doc/BUILDING.md)
