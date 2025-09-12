package source.hanger.processor.agent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;

import com.sun.jna.Pointer;
import lombok.extern.slf4j.Slf4j;
import org.agrona.BitUtil;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import source.hanger.jna.DeepFilterNetNativeLib;

@Slf4j
public class DeepFilterNetProcessingAgent implements Agent {
    // 修改：将 AudioFormat 字段的访问修饰符改为 public
    public static final AudioFormat AUDIO_FORMAT = new AudioFormat(48000.0f, 16, 1, true, false);
    private static final int MSG_TYPE_ID = 1; // 新增：消息类型ID
    // 48kHz, 16-bit, mono, signed, little-endian
    private final DeepFilterNetNativeLib nativeLib;
    private final Pointer dfState;
    private final int frameLength;
    private final OneToOneRingBuffer ringBuffer;
    private final OneToOneConcurrentArrayQueue<byte[]> listenerOutputQueue;
    private final AtomicBoolean endOfInputSignaled;

    private final ByteBuffer frameAccumulator;
    private final float[] internalInputFloats;

    public DeepFilterNetProcessingAgent(
        DeepFilterNetNativeLib nativeLib,
        Pointer dfState,
        int frameLength,
        OneToOneRingBuffer ringBuffer,
        OneToOneConcurrentArrayQueue<byte[]> listenerOutputQueue,
        AtomicBoolean endOfInputSignaled) {
        this.nativeLib = nativeLib;
        this.dfState = dfState;
        this.frameLength = frameLength;
        this.ringBuffer = ringBuffer;
        this.listenerOutputQueue = listenerOutputQueue;
        this.endOfInputSignaled = endOfInputSignaled;

        // 使用固定的 AUDIO_FORMAT
        final int bytesPerFullFrame = frameLength * AUDIO_FORMAT.getFrameSize();
        final int frameAccumulatorCapacity = BitUtil.findNextPositivePowerOfTwo(
            bytesPerFullFrame + ringBuffer.maxMsgLength());
        log.info(
            "DF_ACCUM_DIAG: ProcessingAgent 构造: bytesPerFullFrame={}, ringBuffer.maxMsgLength()={}, "
                + "frameAccumulatorCapacity={}",
            bytesPerFullFrame, ringBuffer.maxMsgLength(), frameAccumulatorCapacity);
        this.frameAccumulator = ByteBuffer.allocateDirect(frameAccumulatorCapacity);
        this.frameAccumulator.order(AUDIO_FORMAT.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        this.internalInputFloats = new float[frameLength];
    }

    @Override
    public String roleName() {
        return "dfnet-processing-agent";
    }

    @Override
    public int doWork() {
        int workDone = 0;
        // 使用固定的 AUDIO_FORMAT
        final int bytesPerFullFrame = frameLength * AUDIO_FORMAT.getFrameSize();

        // 优先处理已积累的完整帧
        if (frameAccumulator.position() >= bytesPerFullFrame) {
            frameAccumulator.flip();

            for (int i = 0; i < frameLength; i++) {
                internalInputFloats[i] = frameAccumulator.getShort() / 32768.0f;
            }

            int remainingBytes = frameAccumulator.remaining();
            if (remainingBytes > 0) {
                frameAccumulator.compact();
            } else {
                frameAccumulator.clear();
            }

            float[] outputFloats = new float[frameLength];
            nativeLib.df_process_frame(dfState, internalInputFloats, outputFloats);

            byte[] processedBytes = convertFloatsToBytes(outputFloats, bytesPerFullFrame);

            // 将降噪后的数据放入监听队列，由 listenerThread 异步处理
            // 这里不再需要 Thread.sleep 或 yield，因为 AgentRunner 的 IdleStrategy 会处理空闲
            while (!listenerOutputQueue.offer(processedBytes)) {
                // 如果队列满，短暂让出 CPU，避免忙等，等待 listener agent 消费
                Thread.yield();
            }
            workDone = 1; // 至少完成了一项工作
        }

        // 如果没有完整帧可处理，则尝试从 Ring Buffer 读取数据
        int messagesRead = ringBuffer.read((msgTypeId, buffer, index, length) -> {
            if (msgTypeId == MSG_TYPE_ID) {
                if (frameAccumulator.remaining() >= length) {
                    buffer.getBytes(index, frameAccumulator, length); // 修正：移除第三个参数 offset
                } else {
                    // log.info(
                    //    "DF_ACCUM_DIAG: processLoop: frameAccumulator 空间不足，无法完全读取 Ring Buffer 消息。消息长度: {}, "
                    //        + "frameAccumulator.remaining(): {}",
                    //    length, frameAccumulator.remaining());
                }
            }
        }, 1); // 每次只尝试读取一个消息

        if (messagesRead > 0) {
            workDone = 1; // 至少完成了一项工作
        }

        // 优雅退出条件：收到输入结束信号，ringBuffer已空，且frameAccumulator已清空
        if (endOfInputSignaled.get() && ringBuffer.size() == 0 && frameAccumulator.position() == 0) {
            // 确保在 agent 退出前，所有剩余数据都被 flush
            flushRemainingRingBufferData(frameAccumulator);
            if (frameAccumulator.position() == 0) {
                return 0; // 表示已完成所有工作，AgentRunner 可以停止此 Agent
            } else {
                // 如果刷新后依然有数据，可能是逻辑错误，或者需要更多轮询来清空
                return 1; // 尝试再做一轮工作
            }
        }

        return workDone; // 返回完成的工作量
    }

    private void flushRemainingRingBufferData(ByteBuffer frameAccumulator) {
        // 使用固定的 AUDIO_FORMAT
        final int bytesPerFullFrame = frameLength * AUDIO_FORMAT.getFrameSize();

        ringBuffer.read((msgTypeId, buffer, index, length) -> {
            if (msgTypeId == MSG_TYPE_ID) {
                if (frameAccumulator.remaining() >= length) {
                    buffer.getBytes(index, frameAccumulator, length); // 修正 Agrona 的 getBytes 调用
                } else {
                    // log.info(
                    //    "DF_ACCUM_DIAG: flushRemainingRingBufferData: frameAccumulator 空间不足，无法完全读取 Ring Buffer "
                    //        + "剩余消息。剩余消息长度:"
                    //        + " {}, frameAccumulator.remaining(): {}",
                    //    length, frameAccumulator.remaining());
                }
            }
        }, Integer.MAX_VALUE);

        if (frameAccumulator.position() > 0) {
            frameAccumulator.flip();
            int currentBufferedBytes = frameAccumulator.remaining();

            if (currentBufferedBytes < bytesPerFullFrame) {
                frameAccumulator.position(currentBufferedBytes);
                frameAccumulator.limit(bytesPerFullFrame);
                for (int i = currentBufferedBytes; i < bytesPerFullFrame; i++) {
                    frameAccumulator.put((byte)0);
                }
                frameAccumulator.rewind();
            } else {
                frameAccumulator.rewind();
                // log.info(
                //    "DF_ACCUM_DIAG: flushRemainingRingBufferData: 发现 frameAccumulator 包含完整或超长帧。实际字节: {}",
                //    currentBufferedBytes);
            }

            for (int i = 0; i < frameLength; i++) {
                internalInputFloats[i] = frameAccumulator.getShort() / 32768.0f;
            }

            float[] outputFloats = new float[frameLength];
            nativeLib.df_process_frame(dfState, internalInputFloats, outputFloats);

            byte[] processedBytes = convertFloatsToBytes(outputFloats, bytesPerFullFrame);

            // 将降噪后的数据放入监听队列，由 listenerThread 异步处理
            while (!listenerOutputQueue.offer(processedBytes)) {
                Thread.yield();
            }

            frameAccumulator.clear(); // 确保 frameAccumulator 在处理完所有剩余数据后被完全清空
        }
    }

    private byte[] convertFloatsToBytes(float[] outputFloats, int bytesPerFullFrame) {
        ByteBuffer outputByteBuffer = ByteBuffer.allocate(bytesPerFullFrame);
        // 使用固定的 AUDIO_FORMAT
        outputByteBuffer.order(AUDIO_FORMAT.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frameLength; i++) {
            short s = (short)(outputFloats[i] * 32768.0f);
            outputByteBuffer.putShort(s);
        }
        return outputByteBuffer.array();
    }
}
