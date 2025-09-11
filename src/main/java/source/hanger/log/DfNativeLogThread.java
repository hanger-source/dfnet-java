package source.hanger.log;

import com.sun.jna.Pointer;
import source.hanger.jna.DeepFilterNetNativeLib;

public class DfNativeLogThread extends Thread {
    private final Pointer state;
    private volatile boolean running = true;

    public DfNativeLogThread(Pointer state) {
        this.state = state;
    }

    public void run() {
        while (running) {
            try {
                Pointer logMsgPtr = DeepFilterNetNativeLib.INSTANCE.df_next_log_msg(state);
                if (logMsgPtr != Pointer.NULL) {
                    String logMsg = logMsgPtr.getString(0);
                    System.out.println("DF_NATIVE_LOG: " + logMsg);
                    DeepFilterNetNativeLib.INSTANCE.df_free_log_msg(logMsgPtr);
                } else {
                    // If no log messages, wait a short time to avoid busy-waiting
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
                System.out.println("DF_LOG_WARN: 日志线程被中断。");
            } catch (Exception e) {
                System.err.println("DF_LOG_ERROR: 读取原生日志时发生错误: " + e.getMessage());
            }
        }
    }

    public void stopLogging() {
        running = false;
        this.interrupt();
    }
}
