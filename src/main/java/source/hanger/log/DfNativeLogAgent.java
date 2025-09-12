package source.hanger.log;

import com.sun.jna.Pointer;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.Agent;
import source.hanger.jna.DeepFilterNetNativeLib;

@Slf4j
public class DfNativeLogAgent implements Agent {
    private final Pointer state;

    public DfNativeLogAgent(Pointer state) {
        this.state = state;
    }

    @Override
    public int doWork() throws Exception {
        int workDone = 0;
        Pointer logMsgPtr = DeepFilterNetNativeLib.INSTANCE.df_next_log_msg(state);
        if (logMsgPtr != Pointer.NULL) {
            String logMsg = logMsgPtr.getString(0);
            System.out.println("DF_NATIVE_LOG: " + logMsg);
            DeepFilterNetNativeLib.INSTANCE.df_free_log_msg(logMsgPtr);
            workDone = 1;
        }
        return workDone;
    }

    @Override
    public String roleName() {
        return "df-native-log-agent";
    }

    @Override
    public void onClose() {
        // 在 Agent 关闭时，可以执行一些清理工作，例如刷新剩余日志
        // 但在这个场景下，df_next_log_msg 每次只取一条，所以不需要额外的刷新逻辑
        System.out.println("DF_LOG_INFO: 日志代理已关闭。");
    }
}
