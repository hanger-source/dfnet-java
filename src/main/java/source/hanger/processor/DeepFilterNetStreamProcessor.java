package source.hanger.processor;

import java.nio.ByteBuffer;
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
import source.hanger.DeepFilterNetServiceInitializer;
import source.hanger.jna.DeepFilterNetLibraryInitializer;
import source.hanger.jna.DeepFilterNetNativeLib;
import source.hanger.model.DeepFilterNetModelManager;
import source.hanger.processor.agent.DeepFilterNetListenerAgent;
import source.hanger.processor.agent.DeepFilterNetProcessingAgent;
import source.hanger.processor.agent.ProcessorOutputGroup;
import source.hanger.util.AudioFrameListener;

@Slf4j
public class DeepFilterNetStreamProcessor {

    private static final int MSG_TYPE_ID = 1;
    private static final long AGENT_SHUTDOWN_TIMEOUT_MS = 500;
    private final OneToOneRingBuffer inputRingBuffer; // 用于接收外部输入音频帧
    private final MutableDirectBuffer tempWriteBuffer; // 用于将传入的 byte[] 包装成 DirectBuffer
    @lombok.Getter
    private final int frameLength;
    private final AgentRunner processingAgentRunner;
    private final DeepFilterNetNativeLib nativeLib;
    private final ProcessorOutputGroup processorOutputGroup;
    private Pointer dfState;
    private Thread processingAgentThread;

    public DeepFilterNetStreamProcessor(
        float attenLim,
        AudioFrameListener denoisedFrameListener,
        int ringBufferCapacity,
        int listenerQueueCapacity) {
        DeepFilterNetServiceInitializer.initialize();

        this.nativeLib = DeepFilterNetLibraryInitializer.getNativeLibraryInstance();
        String processorId = java.util.UUID.randomUUID().toString();

        this.dfState = nativeLib.df_create(DeepFilterNetModelManager.getModelPath(), attenLim, "info");

        if (this.dfState == null || Pointer.nativeValue(this.dfState) == 0) {
            throw new IllegalStateException("DF_LOG_ERROR: 无法创建 DeepFilterNet 状态。");
        }
        this.frameLength = nativeLib.df_get_frame_length(dfState);

        final int alignedDataCapacity = BitUtil.findNextPositivePowerOfTwo(ringBufferCapacity);
        final int totalCapacity = alignedDataCapacity + RingBufferDescriptor.TRAILER_LENGTH;
        AtomicBuffer ringBufferDirectBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(totalCapacity));
        this.inputRingBuffer = new OneToOneRingBuffer(ringBufferDirectBuffer);
        this.tempWriteBuffer = new UnsafeBuffer(new byte[alignedDataCapacity]);

        // 创建并注册 ProcessorOutputGroup
        OneToOneConcurrentArrayQueue<byte[]> listenerOutputQueue = new OneToOneConcurrentArrayQueue<>(
            listenerQueueCapacity);
        AtomicBoolean endOfInputSignaled = new AtomicBoolean(false);

        this.processorOutputGroup = new ProcessorOutputGroup(
            processorId, denoisedFrameListener, listenerOutputQueue, endOfInputSignaled, inputRingBuffer::size
        );

        DeepFilterNetListenerAgent.getInstance().registerProcessor(this.processorOutputGroup);
        final IdleStrategy idleStrategy = new SleepingIdleStrategy(1);

        DeepFilterNetProcessingAgent processingAgent = new DeepFilterNetProcessingAgent(
            this.nativeLib,
            this.dfState,
            this.frameLength,
            this.inputRingBuffer, // Processing Agent 读取这个 RingBuffer
            this.processorOutputGroup.listenerOutputQueue(),
            this.processorOutputGroup.endOfInputSignaled()
        );

        this.processingAgentRunner = new AgentRunner(idleStrategy,
            exception -> log.error("DF_LOG_ERROR: 处理代理出现异常: {}", exception.getMessage(), exception), null,
            processingAgent);
    }

    public void start() {
        processorOutputGroup.endOfInputSignaled().set(false);
        this.processingAgentThread = AgentRunner.startOnThread(processingAgentRunner);
    }

    public void stop() {
        log.info("DF_LOG: DeepFilterNetStreamProcessor 停止中...");
        processorOutputGroup.endOfInputSignaled().set(true);

        if (processingAgentRunner != null) {
            processingAgentRunner.close();
            if (processingAgentThread != null) {
                try {
                    processingAgentThread.join(AGENT_SHUTDOWN_TIMEOUT_MS);
                    log.info("DF_LOG_INFO: 处理代理线程已关闭。");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("DF_WARN: 等待处理代理关闭时被中断。", e);
                }
            }
        }
        release();
    }

    public void signalEndOfInput() {
        processorOutputGroup.endOfInputSignaled().set(true);
    }

    public void release() {
        DeepFilterNetListenerAgent.getInstance().unregisterProcessor(processorOutputGroup.processorId());

        if (dfState != Pointer.NULL) {
            nativeLib.df_free(dfState);
            dfState = Pointer.NULL;
        }
    }

    public boolean isRunning() {
        return !processorOutputGroup.endOfInputSignaled().get() || processorOutputGroup.inputSizeSupplier().get() > 0
            || !processorOutputGroup.listenerOutputQueue().isEmpty();
    }

    public boolean processAudioFrame(ByteBuffer inputBuffer) {
        if (processorOutputGroup.endOfInputSignaled().get()) {
            log.warn("DF_WARN: processAudioFrame: 输入结束信号=true，拒绝处理新帧。");
            return false;
        }

        final int maxMsgLength = inputRingBuffer.maxMsgLength();

        while (inputBuffer.hasRemaining()) {
            int bytesToWrite = Math.min(inputBuffer.remaining(), maxMsgLength);
            inputBuffer.get(tempWriteBuffer.byteArray(), 0, bytesToWrite);

            while (!processorOutputGroup.endOfInputSignaled().get()) {
                boolean offered = inputRingBuffer.write(MSG_TYPE_ID, tempWriteBuffer, 0, bytesToWrite);
                if (offered) {
                    break;
                }
                Thread.yield();
            }
            if (processorOutputGroup.endOfInputSignaled().get()) {
                return false;
            }
        }
        return true;
    }
}
