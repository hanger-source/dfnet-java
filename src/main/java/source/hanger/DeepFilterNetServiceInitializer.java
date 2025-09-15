package source.hanger;

import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.AgentRunner;
import source.hanger.processor.agent.DeepFilterNetListenerAgent;

import java.util.concurrent.TimeUnit;

@Slf4j
public class DeepFilterNetServiceInitializer {

    private static volatile AgentRunner listenerAgentRunner = null;
    private static final long AGENT_SHUTDOWN_TIMEOUT_MS = 1000L; // Agent 关闭超时时间
    private static final Object lock = new Object();

    private DeepFilterNetServiceInitializer() {
        // Prevent instantiation
    }

    public static void initialize() {
        if (listenerAgentRunner == null) {
            synchronized (lock) {
                if (listenerAgentRunner == null) {
                    log.info("DF_LOG: Initializing DeepFilterNetServiceInitializer.");
                    listenerAgentRunner = DeepFilterNetListenerAgent.startAgentRunner();
                    // 注册一个 JVM 关闭钩子，确保在应用程序关闭时优雅关闭 AgentRunner
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        if (listenerAgentRunner != null) {
                            log.info("DF_LOG: Shutting down DeepFilterNetListenerAgent AgentRunner via shutdown hook.");
                            listenerAgentRunner.close();
                            // AgentRunner.close() 会尝试优雅关闭，但没有 awaitTermination 方法
                            // 依赖 DeepFilterNetListenerAgent.onClose() 中的虚拟线程池关闭逻辑
                        }
                    }, "dfnet-listener-agent-shutdown-hook"));
                    log.info("DF_LOG: DeepFilterNetServiceInitializer initialization complete.");
                }
            }
        }
    }

    public static void shutdown() {
        if (listenerAgentRunner != null) {
            synchronized (lock) {
                if (listenerAgentRunner != null) {
                    log.info("DF_LOG: Explicitly shutting down DeepFilterNetListenerAgent AgentRunner.");
                    listenerAgentRunner.close();
                    listenerAgentRunner = null;
                }
            }
        }
    }

    // 提供一个方法来检查 AgentRunner 是否已经初始化，主要用于测试或调试
    public static boolean isInitialized() {
        return listenerAgentRunner != null;
    }
}
