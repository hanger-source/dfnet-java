package source.hanger.processor.agent;

import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.Agent;
import source.hanger.log.DfNativeLogAgent;

@Slf4j
public class CompositeIoLogAgent implements Agent {

    private final DeepFilterNetListenerAgent listenerAgent;
    private final DfNativeLogAgent logAgent;

    public CompositeIoLogAgent(DeepFilterNetListenerAgent listenerAgent, DfNativeLogAgent logAgent) {
        this.listenerAgent = listenerAgent;
        this.logAgent = logAgent;
    }

    @Override
    public int doWork() throws Exception {
        int workDone = 0;
        workDone += listenerAgent.doWork(); // 执行监听器代理的工作
        workDone += logAgent.doWork();     // 执行日志代理的工作
        return workDone;
    }

    @Override
    public String roleName() {
        return "composite-io-log-agent";
    }

    @Override
    public void onClose() {
        log.info("DF_LOG_INFO: 组合I/O/日志代理正在关闭...");
        // 依次关闭包含的Agent
        listenerAgent.onClose();
        logAgent.onClose();
        log.info("DF_LOG_INFO: 组合I/O/日志代理已关闭。");
    }
}
