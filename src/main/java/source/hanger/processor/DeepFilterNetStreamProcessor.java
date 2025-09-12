package source.hanger.processor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.jna.Pointer;
import lombok.extern.slf4j.Slf4j;
import org.agrona.BitUtil;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.agrona.concurrent.SleepingIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;
import source.hanger.jna.DeepFilterNetLibraryInitializer;
import source.hanger.jna.DeepFilterNetNativeLib;
import source.hanger.log.DfNativeLogAgent;
import source.hanger.processor.agent.CompositeIoLogAgent;
import source.hanger.processor.agent.DeepFilterNetListenerAgent;
import source.hanger.processor.agent.DeepFilterNetProcessingAgent;
import source.hanger.util.AudioFrameListener;

@Slf4j
public class DeepFilterNetStreamProcessor {

    private static final int MSG_TYPE_ID = 1; // Ring Buffer 消息类型 ID
    private static final String MODEL_RESOURCE_PATH = "models/DeepFilterNet3_onnx.tar.gz"; // JAR 内部模型路径

    private final OneToOneRingBuffer ringBuffer; // Agrona Ring Buffer 作为内部输入缓冲区
    private final MutableDirectBuffer tempWriteBuffer; // 用于将传入的 byte[] 包装成 DirectBuffer
    private final AtomicBoolean endOfInputSignaled = new AtomicBoolean(false); // 指示外部生产者是否已完成数据输入
    private final OneToOneConcurrentArrayQueue<byte[]> listenerOutputQueue; // Agrona 队列，只用于降噪数据
    @lombok.Getter
    private final int frameLength;
    private final AgentRunner processingAgentRunner;
    private final AgentRunner ioLogAgentRunner; // 新增组合 Agent 的 Runner
    private final DeepFilterNetNativeLib nativeLib;
    private Pointer dfState;
    private File modelTempFile; // 用于存储临时模型文件的引用

    public DeepFilterNetStreamProcessor(
        float attenLim,
        String logLevel,
        AudioFrameListener denoisedFrameListener,
        int ringBufferCapacity,
        int listenerQueueCapacity) {
        this.nativeLib = DeepFilterNetLibraryInitializer.getNativeLibraryInstance();

        // 提取 JAR 内部模型资源到临时文件并初始化本地库
        initializeModel(attenLim, logLevel);

        if (this.dfState == null || Pointer.nativeValue(this.dfState) == 0) {
            throw new IllegalStateException("DF_LOG_ERROR: 无法创建 DeepFilterNet 状态。");
        }
        this.frameLength = nativeLib.df_get_frame_length(dfState); // 从原生库获取帧长度

        // Agrona Ring Buffer 初始化
        // 计算 Ring Buffer 实际数据存储的容量 (必须是2的幂)
        final int alignedDataCapacity = BitUtil.findNextPositivePowerOfTwo(ringBufferCapacity);
        // 计算整个 Ring Buffer 的总容量 (数据容量 + Trailer)
        final int totalCapacity = alignedDataCapacity + RingBufferDescriptor.TRAILER_LENGTH;
        AtomicBuffer ringBufferDirectBuffer = new UnsafeBuffer(
            ByteBuffer.allocateDirect(totalCapacity)); // 更改类型为 AtomicBuffer
        this.ringBuffer = new OneToOneRingBuffer(ringBufferDirectBuffer); // 更正 OneToOneRingBuffer 构造函数
        this.tempWriteBuffer = new UnsafeBuffer(new byte[alignedDataCapacity]); // tempWriteBuffer 的大小应该是实际数据存储的容量

        this.listenerOutputQueue = new OneToOneConcurrentArrayQueue<>(listenerQueueCapacity);
        final IdleStrategy idleStrategy = new SleepingIdleStrategy(1); // 避免 CPU 忙等

        DeepFilterNetProcessingAgent processingAgent = new DeepFilterNetProcessingAgent(
            this.nativeLib,
            this.dfState,
            this.frameLength,
            this.ringBuffer,
            this.listenerOutputQueue,
            this.endOfInputSignaled
        );

        DeepFilterNetListenerAgent listenerAgent = new DeepFilterNetListenerAgent(
            denoisedFrameListener,
            this.listenerOutputQueue,
            this.endOfInputSignaled,
            this.ringBuffer
        );
        DfNativeLogAgent logAgent = new DfNativeLogAgent(dfState);

        this.processingAgentRunner = new AgentRunner(idleStrategy,
            exception -> log.error("DF_LOG_ERROR: 处理代理出现异常: {}", exception.getMessage(), exception), null,
            processingAgent);
        // 实例化 DfNativeLogAgent
        // 实例化 CompositeIoLogAgent，将 listenerAgent 和 logAgent 传入
        CompositeIoLogAgent compositeIoLogAgent = new CompositeIoLogAgent(listenerAgent, logAgent);

        // 实例化 ioLogAgentRunner，将 compositeIoLogAgent 作为参数传入
        this.ioLogAgentRunner = new AgentRunner(idleStrategy,
            exception -> log.error("DF_LOG_ERROR: 组合I/O/日志代理出现异常: {}", exception.getMessage(), exception),
            null,
            compositeIoLogAgent);
    }

    /**
     * 从 JAR 内部提取资源到临时文件。
     *
     * @return 创建的临时文件
     * @throws IOException 如果无法读取资源或创建/写入文件
     */
    private static File extractResourceToFile() throws IOException {
        try (InputStream inputStream = DeepFilterNetStreamProcessor.class.getClassLoader().getResourceAsStream(
            DeepFilterNetStreamProcessor.MODEL_RESOURCE_PATH)) {
            if (inputStream == null) {
                throw new FileNotFoundException("JAR 资源未找到: " + DeepFilterNetStreamProcessor.MODEL_RESOURCE_PATH);
            }
            File tempFile = File.createTempFile("df_model_", ".tar.gz");
            tempFile.deleteOnExit(); // 确保 JVM 退出时删除文件
            Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        }
    }

    public void start() {
        endOfInputSignaled.set(false);
        AgentRunner.startOnThread(processingAgentRunner);
        AgentRunner.startOnThread(ioLogAgentRunner); // 启动组合 I/O 和日志代理
    }

    public void stop() {
        log.info("DF_LOG: DeepFilterNetStreamProcessor 停止中...");
        endOfInputSignaled.set(true); // 通知所有 Agent 发送输入结束信号

        if (processingAgentRunner != null) {
            processingAgentRunner.close();
        }
        if (ioLogAgentRunner != null) {
            ioLogAgentRunner.close();
            log.info("DF_LOG_INFO: 组合I/O/日志代理已关闭。");
        }
        release();
    }

    public void signalEndOfInput() {
        endOfInputSignaled.set(true); // 仅表示收到输入结束信号，不立即停止处理
    }

    public void release() {
        if (dfState != Pointer.NULL) {
            nativeLib.df_free(dfState);
            dfState = Pointer.NULL;
        }
        if (modelTempFile != null && modelTempFile.exists()) {
            if (modelTempFile.delete()) {
                log.info("DF_INFO: 临时模型文件已删除: {}", modelTempFile.getAbsolutePath());
            } else {
                log.warn("DF_WARN: 无法删除临时模型文件: {}", modelTempFile.getAbsolutePath());
            }
        }
        // 新增：调用本地库清理方法
        DeepFilterNetLibraryInitializer.releaseNativeLibrary();
    }

    public boolean isRunning() {
        // 当输入结束信号=ture，且 ringBuffer 和 listenerOutputQueue 都为空时，才认为是停止
        return !endOfInputSignaled.get() || ringBuffer.size() > 0 || !listenerOutputQueue.isEmpty();
    }

    public boolean processAudioFrame(ByteBuffer inputBuffer) {
        if (endOfInputSignaled.get()) {
            log.warn("DF_WARN: processAudioFrame: 输入结束信号=true，拒绝处理新帧。");
            return false;
        }

        final int maxMsgLength = ringBuffer.maxMsgLength();

        while (inputBuffer.hasRemaining()) {
            int bytesToWrite = Math.min(inputBuffer.remaining(), maxMsgLength);
            // 从传入的 ByteBuffer 读取数据到 tempWriteBuffer
            inputBuffer.get(tempWriteBuffer.byteArray(), 0, bytesToWrite);

            while (!endOfInputSignaled.get()) { // 增加 endOfInputSignaled 检查
                boolean offered = ringBuffer.write(MSG_TYPE_ID, tempWriteBuffer, 0, bytesToWrite);
                if (offered) {
                    break;
                }
                Thread.yield(); // 如果 Ring Buffer 满，短暂让出 CPU
            }
            if (endOfInputSignaled.get()) { // 如果在等待过程中生产者完成，则退出
                return false;
            }
        }
        return true;
    }

    /**
     * 从 JAR 内部提取模型资源到临时文件，并初始化本地库的 DeepFilterNet 状态。
     *
     * @param attenLim 衰减限制
     * @param logLevel 日志级别
     * @throws UncheckedIOException 如果无法提取模型资源
     */
    private void initializeModel(float attenLim, String logLevel) {
        String actualModelPath;
        try {
            this.modelTempFile = extractResourceToFile();
            actualModelPath = this.modelTempFile.getAbsolutePath();
            log.info("DF_INFO: 模型已提取到临时文件: {}", actualModelPath);
        } catch (IOException e) {
            log.error("DF_ERROR: 无法提取模型资源: {}", e.getMessage());
            throw new UncheckedIOException("无法提取模型资源", e);
        }
        this.dfState = nativeLib.df_create(actualModelPath, attenLim, logLevel);
    }

}
