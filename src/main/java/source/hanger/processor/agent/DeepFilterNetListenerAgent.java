package source.hanger.processor.agent;

import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import source.hanger.util.AudioFrameListener;

@Slf4j
public class DeepFilterNetListenerAgent implements Agent {
    private final AudioFrameListener denoisedFrameListener;
    private final OneToOneConcurrentArrayQueue<byte[]> listenerOutputQueue;
    private final AtomicBoolean endOfInputSignaled;
    private final OneToOneRingBuffer ringBuffer; // 用于判断ringBuffer是否清空，以辅助优雅退出

    public DeepFilterNetListenerAgent(
        AudioFrameListener denoisedFrameListener,
        OneToOneConcurrentArrayQueue<byte[]> listenerOutputQueue,
        AtomicBoolean endOfInputSignaled,
        OneToOneRingBuffer ringBuffer) {
        this.denoisedFrameListener = denoisedFrameListener;
        this.listenerOutputQueue = listenerOutputQueue;
        this.endOfInputSignaled = endOfInputSignaled;
        this.ringBuffer = ringBuffer;
    }

    @Override
    public String roleName() {
        return "dfnet-listener-agent";
    }

    @Override
    public int doWork() {
        int workDone = 0;
        byte[] denoisedBytes = listenerOutputQueue.poll();

        if (denoisedBytes != null) {
            denoisedFrameListener.onDenoisedAudioFrame(denoisedBytes, 0, denoisedBytes.length);
            workDone = 1; // 至少完成了一项工作
        } else if (endOfInputSignaled.get() && listenerOutputQueue.isEmpty() && ringBuffer.size() == 0) {
            // 优雅退出条件：收到输入结束信号，监听队列已空，且 ringBuffer 也已空 (确保所有处理线程都已完成)
            // log.info("DF_INFO: DeepFilterNet Listener Agent 已停止，所有数据已处理完毕。");
            return 0; // 表示已完成所有工作，AgentRunner 可以停止此 Agent
        }
        return workDone; // 返回完成的工作量
    }
}
