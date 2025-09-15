package source.hanger.processor.agent;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;

@Slf4j
public class DeepFilterNetListenerAgent implements Agent {

    private static final long AGENT_SHUTDOWN_TIMEOUT_MS = 500L;
    private static final DeepFilterNetListenerAgent INSTANCE = new DeepFilterNetListenerAgent();

    private final ConcurrentHashMap<String, ProcessorOutputGroup> processorGroups = new ConcurrentHashMap<>();
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private DeepFilterNetListenerAgent() {
        // 私有构造函数，实现单例
    }

    public static DeepFilterNetListenerAgent getInstance() {
        return INSTANCE;
    }

    // 静态方法来启动 AgentRunner
    public static AgentRunner startAgentRunner() {
        final IdleStrategy idleStrategy = new SleepingIdleStrategy(1); // 避免 CPU 忙等

        // 实现 ErrorHandler 接口
        final ErrorHandler errorHandler = (throwable) -> {
            log.error("DF_LOG_ERROR: Agrona AgentRunner Error: {}", throwable.getMessage(), throwable);
        };

        // 创建 AtomicCounter
        final AtomicBuffer counterBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(8)); // 8 bytes for a long
        final AtomicCounter errorCounter = new AtomicCounter(counterBuffer, 0);

        // AgentRunner 的构造函数是 AgentRunner(IdleStrategy idleStrategy, ErrorHandler errorHandler, AtomicCounter
        // errorCounter, Agent agent)
        final AgentRunner agentRunner = new AgentRunner(
            idleStrategy,
            errorHandler,
            errorCounter,
            DeepFilterNetListenerAgent.getInstance()
        );
        AgentRunner.startOnThread(agentRunner);
        log.info("DF_LOG: DeepFilterNetListenerAgent started on a dedicated thread.");
        return agentRunner;
    }

    public void registerProcessor(ProcessorOutputGroup group) {
        if (processorGroups.putIfAbsent(group.processorId(), group) == null) {
            log.info("DF_LOG: Processor {} registered with DeepFilterNetListenerAgent.", group.processorId());
        } else {
            log.warn("DF_LOG: Processor {} already registered with DeepFilterNetListenerAgent. Ignoring.",
                group.processorId());
        }
    }

    public void unregisterProcessor(String processorId) {
        if (processorGroups.remove(processorId) != null) {
            log.info("DF_LOG: Processor {} unregistered from DeepFilterNetListenerAgent.", processorId);
        } else {
            log.warn("DF_LOG: Attempted to unregister unknown processor {}. Ignoring.", processorId);
        }
    }

    @Override
    public String roleName() {
        return "dfnet-listener-agent";
    }

    @Override
    public int doWork() {
        int workDone = 0;
        for (ProcessorOutputGroup group : processorGroups.values()) {
            byte[] denoisedBytes = group.listenerOutputQueue().poll();

            if (denoisedBytes != null) {
                virtualThreadExecutor.submit(() -> {
                    synchronized (group) {
                        try {
                            group.denoisedFrameListener().onDenoisedAudioFrame(denoisedBytes, 0, denoisedBytes.length);
                        } catch (Throwable e) {
                            log.error("DF_LOG: Error in denoisedFrameListener callback for processor {}: {}",
                                group.processorId(), e.getMessage(), e);
                        }
                    }
                });
                workDone = 1; // 至少完成了一项工作
            } else if (group.endOfInputSignaled().get() && group.listenerOutputQueue().isEmpty()
                && group.inputSizeSupplier().get() == 0) {
                // 优雅退出条件：收到输入结束信号，监听队列已空，且 ringBuffer 也已空 (确保所有处理线程都已完成)
            }
        }
        return workDone;
    }

    @Override
    public void onClose() {
        log.info("DF_LOG: Shutting down DeepFilterNetListenerAgent and virtual thread executor.");
        virtualThreadExecutor.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(AGENT_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("DF_LOG: Virtual thread executor did not terminate in time. Forcing shutdown.");
                virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("DF_LOG: Interrupted during virtual thread executor shutdown.", e);
        }
        log.info("DF_LOG: DeepFilterNetListenerAgent onClose completed.");
    }
}
