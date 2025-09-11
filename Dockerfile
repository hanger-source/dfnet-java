# Stage 1: Build libdf.so
FROM --platform=linux/amd64 rust:1.89-slim-bullseye AS rust_builder

WORKDIR /app
RUN mkdir -p models
COPY models/DeepFilterNet3_onnx.tar.gz ./models/
WORKDIR /app/libDF

# 安装 libdf 编译所需的系统依赖项
RUN apt-get update && apt-get install -y \
    libsndfile1-dev \
    pkg-config \
    build-essential \
    && rm -rf /var/lib/apt/lists/*
# 根据 DeepFilterNet 的 Rust 库的具体需求，可能需要添加其他构建依赖项

# 复制 libdf 源代码
COPY libDF ./

# 在发布模式下编译 libdf.so
RUN cargo build --release --target x86_64-unknown-linux-gnu --features capi

# Stage 2: Build and Run Java application
FROM --platform=linux/amd64 openjdk:21-jdk-slim-bullseye

WORKDIR /app

# 安装 Maven 和 libdf.so 运行时所需的系统依赖项
RUN apt-get update && apt-get install -y \
    maven \
    libsndfile1 \
    binutils \
    && rm -rf /var/lib/apt/lists/*
# libdf.so 的运行时依赖
# 根据 libdf.so 的具体需求，可能需要添加其他运行时依赖项

# 为本地库创建目录
RUN mkdir -p lib/linux-x64

# 从 rust_builder 阶段复制编译好的 libdf.so 到 JNA 期望的位置
COPY --from=rust_builder /app/libDF/target/x86_64-unknown-linux-gnu/release/libdf.so ./lib/linux/x86_64/

# 创建模型和数据目录
RUN mkdir -p models data out

# 复制项目文件、模型和数据
COPY pom.xml ./
COPY src ./src
COPY models/DeepFilterNet3_onnx.tar.gz ./models/
COPY data/speech_with_noise_48k.wav ./data/

# 构建 Java 项目
# 跳过测试以加快 Docker 镜像的构建速度
RUN mvn clean install -DskipTests

# 定义运行演示的命令
# 显式设置 jna.library.path 以确保 Docker 中路径的一致性，覆盖 pom.xml 中的动态路径设置
ENV JAVA_TOOL_OPTIONS="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED"
CMD ["/bin/bash", "-c", "nm -D /app/lib/linux/x86_64/libdf.so || echo 'DF_LOG: nm command failed.' && echo '--- Running Java Demo ---' && mvn exec:java -X -Dexec.mainClass=source.hanger.demo.RealtimeDenoiseDemo -Djna.library.path=/app/lib/linux/x86_64"]
