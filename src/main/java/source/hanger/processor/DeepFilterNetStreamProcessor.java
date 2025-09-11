package source.hanger.processor;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;

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
import source.hanger.log.DfNativeLogThread;
import source.hanger.processor.agent.DeepFilterNetListenerAgent;
import source.hanger.processor.agent.DeepFilterNetProcessingAgent;
import source.hanger.util.AudioFrameListener;

@Slf4j
public class DeepFilterNetStreamProcessor {

    private static final int MSG_TYPE_ID = 1; // Ring Buffer 消息类型 ID

    private final DfNativeLogThread logThread;
    private final OneToOneRingBuffer ringBuffer; // Agrona Ring Buffer 作为内部输入缓冲区
    private final MutableDirectBuffer tempWriteBuffer; // 用于将传入的 byte[] 包装成 DirectBuffer
    private final AtomicBoolean endOfInputSignaled = new AtomicBoolean(false); // 指示外部生产者是否已完成数据输入
    private final OneToOneConcurrentArrayQueue<byte[]> listenerOutputQueue; // Agrona 队列，只用于降噪数据
    @lombok.Getter
    private final int frameLength; // 声明为 final
    private final DeepFilterNetProcessingAgent processingAgent; // 声明为成员变量
    private final DeepFilterNetListenerAgent listenerAgent;   // 声明为成员变量
    private final AgentRunner processingAgentRunner;
    private final AgentRunner listenerAgentRunner;
    private final DeepFilterNetNativeLib nativeLib;
    private Pointer dfState;

    public DeepFilterNetStreamProcessor(
        AudioFormat audioFormat,
        String modelPath,
        float attenLim,
        String logLevel,
        AudioFrameListener denoisedFrameListener,
        int ringBufferCapacity,
        int listenerQueueCapacity) {
        this.nativeLib = DeepFilterNetLibraryInitializer.getNativeLibraryInstance();
        this.dfState = nativeLib.df_create(modelPath, attenLim, logLevel); // 修正 df_create 参数
        if (this.dfState == null || Pointer.nativeValue(this.dfState) == 0) {
            throw new IllegalStateException("DF_LOG_ERROR: 无法创建 DeepFilterNet 状态。");
        }
        this.frameLength = nativeLib.df_get_frame_length(dfState); // 从原生库获取帧长度
        // 用于回调降噪后的音频帧

        // log.info("DeepFilterNetStreamProcessor 正在初始化...");

        // log.info("DeepFilterNet 状态已创建: {}", this.dfState);

        // 替换 startNativeLogThread()
        this.logThread = new DfNativeLogThread(dfState);
        this.logThread.start();

        // Agrona Ring Buffer 初始化
        // 计算 Ring Buffer 实际数据存储的容量 (必须是2的幂)
        final int alignedDataCapacity = BitUtil.findNextPositivePowerOfTwo(ringBufferCapacity);
        // 计算整个 Ring Buffer 的总容量 (数据容量 + Trailer)
        final int totalCapacity = alignedDataCapacity + RingBufferDescriptor.TRAILER_LENGTH;
        AtomicBuffer ringBufferDirectBuffer = new UnsafeBuffer(
            ByteBuffer.allocateDirect(totalCapacity)); // 更改类型为 AtomicBuffer
        this.ringBuffer = new OneToOneRingBuffer(ringBufferDirectBuffer); // 更正 OneToOneRingBuffer 构造函数
        this.tempWriteBuffer = new UnsafeBuffer(new byte[alignedDataCapacity]); // tempWriteBuffer 的大小应该是实际数据存储的容量
        // log.info(
        //    "Agrona Ring Buffer 初始化完成. Total Capacity: {} bytes, Data Capacity (Max Message Length): {} bytes",
        //    totalCapacity, alignedDataCapacity);

        // Agrona OneToOneConcurrentArrayQueue for listener output
        this.listenerOutputQueue = new OneToOneConcurrentArrayQueue<>(listenerQueueCapacity);
        // log.info("Agrona Listener Output Queue 初始化完成. Capacity: {}", listenerQueueCapacity);

        final IdleStrategy idleStrategy = new SleepingIdleStrategy(1); // 避免 CPU 忙等

        this.processingAgent = new DeepFilterNetProcessingAgent(
            audioFormat,
            this.nativeLib,
            this.dfState,
            this.frameLength,
            this.ringBuffer,
            this.listenerOutputQueue,
            this.endOfInputSignaled
        );

        this.listenerAgent = new DeepFilterNetListenerAgent(
            denoisedFrameListener,
            this.listenerOutputQueue,
            this.endOfInputSignaled,
            this.ringBuffer
        );
        this.processingAgentRunner = new AgentRunner(idleStrategy, Throwable::printStackTrace, null,
            this.processingAgent);
        this.listenerAgentRunner = new AgentRunner(idleStrategy, Throwable::printStackTrace, null, this.listenerAgent);
    }

    public void start() {
        endOfInputSignaled.set(false);
        // log.info("DeepFilterNetStreamProcessor 启动 AgentRunner...");
        new Thread(processingAgentRunner, processingAgent.roleName()).start(); // 修正 roleName() 调用
        new Thread(listenerAgentRunner, listenerAgent.roleName()).start(); // 修正 roleName() 调用
    }

    public void stop() {
        // log.info("DeepFilterNetStreamProcessor 停止中...");
        endOfInputSignaled.set(true); // 通知所有 Agent 发送输入结束信号

        if (processingAgentRunner != null) {
            processingAgentRunner.close();
            // log.info("处理AgentRunner已关闭。");
        }
        if (listenerAgentRunner != null) {
            listenerAgentRunner.close();
            // log.info("监听AgentRunner已关闭。");
        }

        release();
        // log.info("DeepFilterNetStreamProcessor 已停止。");
    }

    public void signalEndOfInput() {
        endOfInputSignaled.set(true); // 仅表示收到输入结束信号，不立即停止处理
    }

    public void release() {
        if (dfState != Pointer.NULL) {
            nativeLib.df_free(dfState);
            dfState = Pointer.NULL;
        }
        if (logThread != null) {
            logThread.stopLogging();
            try {
                logThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        // 当输入结束信号=ture，且 ringBuffer 和 listenerOutputQueue 都为空时，才认为是停止
        return !endOfInputSignaled.get() || ringBuffer.size() > 0 || !listenerOutputQueue.isEmpty();
    }

    public boolean processAudioFrame(byte[] inputBytes) {
        if (endOfInputSignaled.get()) {
            // log.warn("DF_WARN: processAudioFrame: 输入结束信号=true，拒绝处理新帧。");
            return false;
        }

        final int maxMsgLength = ringBuffer.maxMsgLength();
        int offset = 0;

        while (offset < inputBytes.length) {
            int bytesToWrite = Math.min(inputBytes.length - offset, maxMsgLength);
            tempWriteBuffer.putBytes(0, inputBytes, offset, bytesToWrite);

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
            offset += bytesToWrite;
        }
        return true;
    }

}
