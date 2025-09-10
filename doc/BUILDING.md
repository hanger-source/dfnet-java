# 构建 `dfnet-java` 项目及本地库

`dfnet-java` 项目依赖于一个高性能的 Rust 本地库 `libdf`，以及 Java Native Access (JNA) 来实现 Java 与 Rust 之间的互操作。本文档将详细说明如何构建这些组件。

## 1. 构建 Rust 本地库 (`libdf`)

`libdf` 是 DeepFilterNet 的核心降噪逻辑的 Rust 实现，它通过 C FFI (Foreign Function Interface) 暴露接口，供 Java **通过 JNA** 调用。

### 前提条件

*   **Rust 工具链：** 你需要安装 Rust 编程语言及其包管理器 Cargo。推荐使用 `rustup` 进行安装：
    ```bash
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
    ```
    安装完成后，请确保 Rust 环境已正确配置到你的 PATH 中，例如在 `~/.zshrc` 或 `~/.bashrc` 中添加：
    ```bash
    source $HOME/.cargo/env
    ```

### 构建步骤

1.  **克隆 DeepFilterNet 仓库：** `libdf` 是 DeepFilterNet 官方仓库的一部分，你需要克隆整个仓库来访问 `libDF` 源代码。
    ```bash
    git clone https://github.com/Rikorose/DeepFilterNet.git
    cd DeepFilterNet
    ```

2.  **构建 `libdf`：** 切换到 `libDF` 目录并使用 `cargo` 构建。**务必启用 `capi` 特性**，这样 Rust 才能导出 C 兼容的函数。
    ```bash
    cd libDF
    cargo build --release --features capi
    ```
    这将会在 `DeepFilterNet/target/release/` 目录下生成本地库文件。
    *   **macOS：** `libdf.dylib`
    *   **Linux：** `libdf.so`
    *   **Windows：** `df.dll`

3.  **放置本地库：** 将生成的本地库文件复制到 `dfnet-java` 项目的相应平台目录下。
    例如，对于 macOS (ARM64)：
    ```bash
    cp /path/to/DeepFilterNet/target/release/libdf.dylib /path/to/dfnet-java/lib/macos-aarch64/
    ```
    请根据你的操作系统和架构，将 `libdf.dylib` 替换为 `libdf.so` 或 `df.dll`，并将目标目录调整为 `lib/linux-x64/` 或 `lib/windows-x64/` 等。

## 2. JNA 绑定原理及实现

JNA (Java Native Access) 提供了一种无需编写 JNI (Java Native Interface) 代码即可从 Java 应用程序访问共享库的机制。它通过动态地将 Java 接口映射到本地函数来实现。

### 核心组件

*   **`DeepFilterNetNativeLib.java`：**
    这个 Java 接口定义了 Rust C API 暴露的函数签名。JNA 会在运行时动态地创建这个接口的实现，将 Java 方法调用桥接到本地库的相应 C 函数。
    *   **方法签名：** Java 接口中的方法签名必须与 `DeepFilterNet/libDF/src/capi.rs` 中 `pub unsafe extern "C"` 声明的函数签名精确匹配，包括参数类型和返回类型。JNA 会自动处理大部分基本数据类型（如 `float`, `int`, `String`）。
    *   **`Pointer`：** 在 C API 中，`*mut DFState` (一个指向 `DFState` 结构体的指针) 在 JNA 中被映射为 `com.sun.jna.Pointer` 类型。JNA 的 `Pointer` 类代表一个原生内存地址。
    *   **`static` 初始化块：** 在 `DeepFilterNetNativeLib` 接口中，我们使用了一个 `static` 初始化块来动态设置 `jna.library.path` 系统属性。这个属性告诉 JNA 在哪里查找本地库文件 (例如 `libdf.dylib`)。它会根据当前运行的操作系统和架构，构建出正确的 `lib/platform-arch` 路径，确保 JNA 能够找到并加载本地库。

*   **`DeepFilterNetProcessor.java`：**
    这个类是 `dfnet-java` 的核心业务逻辑封装。它负责：
    *   **模型加载和初始化：** 通过调用 `DeepFilterNetNativeLib.INSTANCE.df_create(...)` 来创建 DeepFilterNet 模型实例。
    *   **音频帧处理：** 实现 `denoiseWavFile` 方法，通过循环读取 WAV 文件，将音频数据转换为 `float[]`，然后调用 `DeepFilterNetNativeLib.INSTANCE.df_process_frame(...)` 进行降噪处理，最后将处理后的数据写入输出 WAV 文件。
    *   **资源管理：** 确保在处理完成后调用 `DeepFilterNetNativeLib.INSTANCE.df_free(...)` 释放 Rust 分配的资源。

### 数据类型映射

JNA 提供了 Java 类型与 C 语言类型之间方便的自动映射。以下是一些常见映射的示例：

| C 类型            | Rust 类型 (`capi.rs`)       | JNA Java 类型               |
| :---------------- | :-------------------------- | :-------------------------- |
| `void*`           | `*mut DFState`              | `com.sun.jna.Pointer`       |
| `const char*`     | `*const c_char`             | `String`                    |
| `float`           | `c_float`                   | `float`                     |
| `int`             | `usize` (通常是 `isize`) | `int`                       |
| `float*`          | `*mut c_float`              | `float[]` (作为数组传递) |

JNA 在底层负责将 Java 数组（如 `float[]`）的内存地址传递给 C 函数，并在需要时进行数据复制。

## 3. 构建 Java 项目

构建 `dfnet-java` 项目需要 Maven。

### 前提条件

*   **JDK 11+：** 确保你的系统安装了 Java Development Kit (JDK) 11 或更高版本。
*   **Maven：** 安装 Apache Maven。你可以从官方网站下载，或者使用包管理器（如 Homebrew 在 macOS 上，`sudo apt-get install maven` 在 Debian/Ubuntu 上）。

### 构建步骤

1.  **导航到项目根目录：**
    ```bash
    cd /path/to/dfnet-java
    ```

2.  **安装依赖并编译：**
    ```bash
    mvn clean install
    ```
    这将下载 JNA 依赖，编译 Java 源代码，并将项目安装到你的本地 Maven 仓库中。

3.  **运行示例：**
    你可以使用 Maven 的 `exec-maven-plugin` 来运行 `DenoiseWavFile` 示例：
    ```bash
    mvn exec:java
    ```
    这会执行 `DenoiseWavFile` 类中的 `main` 方法，处理 `data/speech_with_noise_48k.wav` 文件，并将降噪后的输出保存到 `out/speech_with_noise_48k_by_java.wav`。

通过遵循这些步骤，你应该能够成功构建 `dfnet-java` 项目，并理解其本地库和 JNA 绑定的工作原理。
