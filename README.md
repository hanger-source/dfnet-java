# dfnet-java: DeepFilterNet Java JNA 封装

`dfnet-java` 提供了一个 Java 原生访问 (JNA) 封装，用于集成高性能的 [DeepFilterNet](https://github.com/Rikorose/DeepFilterNet) 降噪库。它允许 Java 应用程序通过 [Java Native Access (JNA)](https://github.com/java-native-access/jna) 库，**借助 Rust 的 [Tract](https://github.com/sonos/tract) 推理引擎**，以编程方式利用 DeepFilterNet 核心 Rust 库的实时音频处理能力。

## ✨ 特性

*   **基于 DeepFilterNet 核心 Rust 库：** 利用 [DeepFilterNet](https://github.com/Rikorose/DeepFilterNet) 提供的高性能音频处理。
*   **易于集成：** 作为 Maven 依赖项轻松集成到任何 Java 项目中。
*   **跨平台支持：** 支持 macOS (ARM), Linux (x64) 等多个平台，通过动态加载平台特定的本地库。
*   **简洁 API：** 提供 `DeepFilterNetProcessor` 类，封装了模型加载、音频处理和资源释放。

## 🚀 系统要求

*   **Java Development Kit (JDK):** 21 或更高版本。
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
│           └── source/
│               └── hanger/
│                   ├── demo/
│                   │   ├── MicrophoneDenoiseDemo.java
│                   │   ├── RealtimeDenoiseDemo.java
│                   │   └── WavFileDenoiseDemo.java
│                   ├── DeepFilterNetNativeLib.java
│                   ├── DeepFilterNetProcessor.java
│                   ├── DeepFilterNetStreamProcessor.java
│                   ├── DfNativeLogThread.java
│                   └── WavFileWriter.java
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

`dfnet-java` 依赖于 DeepFilterNet 核心 Rust 库 (`libdf`)。你需要自行编译 `libdf` 以生成平台特定的动态链接库文件 (例如 macOS 上的 `libdf.dylib` 或 Linux 上的 `libdf.so`)。请参考 DeepFilterNet 官方仓库的指南 ([Rikorose/DeepFilterNet](https://github.com/Rikorose/DeepFilterNet))，**或者参考本文档的 Docker 构建部分。**

**重要提示：** `libdf` 源码通常是 DeepFilterNet 工作区 (`DeepFilterNet/`) 的一部分。为了确保正确的依赖版本锁定，在编译 `libdf` 时，可能需要将原始 DeepFilterNet 工作区中的 `Cargo.lock` 文件复制到 `libdf` 所在目录的上一级。例如，如果 `libdf` 在 `/your/project/libDF/`，则将 `DeepFilterNet/Cargo.lock` 复制到 `/your/project/Cargo.lock`。

一旦编译完成，你需要将编译好的 `libdf` 库复制到 `dfnet-java` 项目的 `lib` 目录中对应的平台子目录：

*   **macOS (ARM):** 将 `libdf.dylib` 复制到 `dfnet-java/lib/macos/aarch64/`。
*   **Linux (x64):** 将 `libdf.so` 复制到 `dfnet-java/lib/linux/x86_64/`。

**示例复制命令 (假设你在 DeepFilterNet 仓库的 `target/release` 目录下，或者在自定义的 `libDF` 编译目录的 `target/release` 目录下)：**

*   **对于 macOS (ARM):**
    ```bash
    cp libdf.dylib /path/to/dfnet-java/lib/macos/aarch64/
    ```
*   **对于 Linux (x64):**
    ```bash
    cp libdf.so /path/to/dfnet-java/lib/linux/x86_64/
    ```

## 🐳 使用 Docker 构建 `libdf` (Linux x64)

本项目提供了一个 `Dockerfile`，允许你在一个隔离的 Docker 容器中构建 `libdf` 库，特别适用于 Linux x64 环境。这可以简化依赖管理和环境配置。

1.  **准备 Rust 源码：**
    确保 `DeepFilterNet` 的 Rust 核心库 (`libDF` 目录) 及其父目录的 `Cargo.lock` 文件位于 `dfnet-java` 项目的根目录下，供 `Dockerfile` 访问。
2.  **构建 Docker 镜像：**
    ```bash
    docker build -t dfnet-java-builder:latest .
    ```
    这个命令会构建一个包含 `libdf.so` 的镜像。构建完成后，你可以从镜像中提取 `libdf.so` 文件，或者运行一个包含该库的容器来执行 Java demo。

3.  **从 Docker 镜像中提取 `libdf.so` (可选)：**
    ```bash
    docker run --rm dfnet-java-builder:latest cat /app/lib/linux/x86_64/libdf.so > lib/linux/x86_64/libdf.so
    ```
    这将 `libdf.so` 从 Docker 镜像中提取到你的本地 `dfnet-java/lib/linux/x86_64/` 目录。

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

`dfnet-java` 提供了一系列示例来展示库的使用。这些示例位于 `src/main/java/source/hanger/demo/` 目录下。

### 1. `WavFileDenoiseDemo.java` (WAV 文件降噪示例)
这个示例展示了如何从一个 WAV 文件读取音频，进行降噪处理，并将降噪前后的音频写入新的 WAV 文件。

**运行方式：**
1.  确保你已按照上述步骤编译 `libdf` (无论是本地编译还是通过 Docker) 并将其复制到正确位置。
2.  确保模型文件 (`models/DeepFilterNet3_onnx.tar.gz`) 和输入 WAV 文件 (`data/speech_with_noise_48k.wav`) 位于 `dfnet-java` 项目的相应子目录。
3.  进入 `dfnet-java` 项目目录：`cd dfnet-java`
4.  运行示例：
    ```bash
    mvn exec:java -Dexec.mainClass="source.hanger.demo.WavFileDenoiseDemo"
    ```
    这将会输出 `out/original_audio.wav` 和 `out/denoised_audio.wav` 文件。

### 2. `RealtimeDenoiseDemo.java` (模拟实时流降噪示例)
这个示例模拟了从文件读取音频流进行实时降噪，并将其写入 WAV 文件。

**运行方式：**
1.  确保你已按照上述步骤编译 `libdf` (无论是本地编译还是通过 Docker) 并将其复制到正确位置。
2.  确保模型文件 (`models/DeepFilterNet3_onnx.tar.gz`) 和输入 WAV 文件 (`data/speech_with_noise_48k.wav`) 位于 `dfnet-java` 项目的相应子目录。
3.  进入 `dfnet-java` 项目目录：`cd dfnet-java`
4.  运行示例：
    ```bash
    mvn exec:java -Dexec.mainClass="source.hanger.demo.RealtimeDenoiseDemo"
    ```
    这将会输出 `out/original_audio_stream.wav` 和 `out/denoised_audio_stream.wav` 文件。

### 3. `MicrophoneDenoiseDemo.java` (实时麦克风降噪示例)
这个示例展示了如何从麦克风捕获实时音频，进行降噪处理，并实时播放降噪后的音频。

**运行方式：**
1.  确保你已按照上述步骤编译 `libdf` (无论是本地编译还是通过 Docker) 并将其复制到正确位置。
2.  确保模型文件 (`models/DeepFilterNet3_onnx.tar.gz`) 位于 `dfnet-java` 项目的相应子目录。
3.  进入 `dfnet-java` 项目目录：`cd dfnet-java`
4.  运行示例：
    ```bash
    mvn exec:java -Dexec.mainClass="source.hanger.demo.MicrophoneDenoiseDemo"
    ```
    按下 `Ctrl+C` 停止程序。

## ⁉️ 故障排除

*   **`java.lang.UnsatisfiedLinkError: Unable to load library 'df'`：**
    *   **原因：** JNA 无法找到 `libdf.dylib` (macOS) 或 `libdf.so` (Linux)。
    *   **解决方案：** 确保 `libdf` 已编译，并将其复制到 `dfnet-java/lib/<os>/<arch>/` 目录下。同时，检查 `pom.xml` 中 `jna.library.path` 的配置是否正确指向该目录 (通过 Maven Profiles 配置 `lib.path.os` 和 `lib.path.arch` 属性)。
*   **Rust `panic` (例如 `not yet implemented`)：**
    *   **原因：** 通常是 DeepFilterNet 模型版本与 `libdf` 所依赖的 `tract` 库版本不兼容。
    *   **解决方案：** 确保你使用的是 `DeepFilterNet3_onnx.tar.gz` 模型。如果问题仍然存在，请检查 `libDF` 源码的 `Cargo.toml` 中 `tract` 依赖的版本，并确保在编译 `libdf` 时使用了正确的 `Cargo.lock` (即原始 `DeepFilterNet` 工作区中的 `Cargo.lock`)。

## 🤝 贡献

欢迎贡献！如果你有任何改进建议或 bug 修复，请随时提交 Pull Request。

## 📄 许可证

本项目根据 MIT 许可证和 Apache-2.0 许可证双重授权。详情请参见 `LICENSE-MIT` 和 `LICENSE-APACHE` 文件。

*   [详细构建指南和 JNA 绑定原理](doc/BUILDING.md)
