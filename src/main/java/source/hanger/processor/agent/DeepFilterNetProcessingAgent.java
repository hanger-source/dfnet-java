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
    private static final int MSG_TYPE_ID = 1; // 新增：消息类型ID
    private final AudioFormat audioFormat;
    private final DeepFilterNetNativeLib nativeLib;
    private final Pointer dfState;
    private final int frameLength;
    private final OneToOneRingBuffer ringBuffer;
    private final OneToOneConcurrentArrayQueue<byte[]> listenerOutputQueue;
    private final AtomicBoolean endOfInputSignaled;

    private final ByteBuffer frameAccumulator;
    private final float[] internalInputFloats;

    public DeepFilterNetProcessingAgent(
        AudioFormat audioFormat,
        DeepFilterNetNativeLib nativeLib,
        Pointer dfState,
        int frameLength,
        OneToOneRingBuffer ringBuffer,
        OneToOneConcurrentArrayQueue<byte[]> listenerOutputQueue,
        AtomicBoolean endOfInputSignaled) {
        this.audioFormat = audioFormat;
        this.nativeLib = nativeLib;
        this.dfState = dfState;
        this.frameLength = frameLength;
        this.ringBuffer = ringBuffer;
        this.listenerOutputQueue = listenerOutputQueue;
        this.endOfInputSignaled = endOfInputSignaled;

        final int bytesPerFullFrame = frameLength * audioFormat.getFrameSize();
        final int frameAccumulatorCapacity = BitUtil.findNextPositivePowerOfTwo(
            bytesPerFullFrame + ringBuffer.maxMsgLength());
        log.info(
            "DF_ACCUM_DIAG: ProcessingAgent 构造: bytesPerFullFrame={}, ringBuffer.maxMsgLength()={}, "
                + "frameAccumulatorCapacity={}",
            bytesPerFullFrame, ringBuffer.maxMsgLength(), frameAccumulatorCapacity);
        this.frameAccumulator = ByteBuffer.allocateDirect(frameAccumulatorCapacity);
        this.frameAccumulator.order(audioFormat.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        this.internalInputFloats = new float[frameLength];
    }

    @Override
    public String roleName() {
        return "dfnet-processing-agent";
    }

    @Override
    public int doWork() {
        int workDone = 0;
        final int bytesPerFullFrame = frameLength * audioFormat.getFrameSize();

        // 优先处理已积累的完整帧
        if (frameAccumulator.position() >= bytesPerFullFrame) {
            // log.info("DF_ACCUM_DIAG: processLoop: 进入帧处理。frameAccumulator.position()={}, bytesPerFullFrame={}",
            //    frameAccumulator.position(), bytesPerFullFrame);
            frameAccumulator.flip();
            // log.info("DF_ACCUM_DIAG: processLoop: flip()后 frameAccumulator.position()={}, limit()={}",
            //    frameAccumulator.position(), frameAccumulator.limit());

            for (int i = 0; i < frameLength; i++) {
                internalInputFloats[i] = frameAccumulator.getShort() / 32768.0f;
            }

            int remainingBytes = frameAccumulator.remaining();
            if (remainingBytes > 0) {
                frameAccumulator.compact();
                // log.info("DF_ACCUM_DIAG: processLoop: compact()后 frameAccumulator.position()={}, limit()={}",
                //    frameAccumulator.position(), frameAccumulator.limit());
            } else {
                frameAccumulator.clear();
                // log.info("DF_ACCUM_DIAG: processLoop: clear()后 frameAccumulator.position()={}, limit()={}",
                //    frameAccumulator.position(), frameAccumulator.limit());
            }

            // long frameProcessStartTime = System.nanoTime(); // 性能诊断开始
            float[] outputFloats = new float[frameLength];
            nativeLib.df_process_frame(dfState, internalInputFloats, outputFloats);

            byte[] processedBytes = convertFloatsToBytes(outputFloats, bytesPerFullFrame);

            // 将降噪后的数据放入监听队列，由 listenerThread 异步处理
            // 这里不再需要 Thread.sleep 或 yield，因为 AgentRunner 的 IdleStrategy 会处理空闲
            while (!listenerOutputQueue.offer(processedBytes)) {
                // 如果队列满，短暂让出 CPU，避免忙等，等待 listener agent 消费
                Thread.yield();
            }
            // log.info("DF_ACCUM_DIAG: processLoop: 处理并提供一个完整帧。listenerOutputQueue.size()={}",
            //    listenerOutputQueue.size());
            workDone = 1; // 至少完成了一项工作
        }

        // 如果没有完整帧可处理，则尝试从 Ring Buffer 读取数据
        int messagesRead = ringBuffer.read((msgTypeId, buffer, index, length) -> {
            if (msgTypeId == MSG_TYPE_ID) {
                // log.info(
                //    "DF_ACCUM_DIAG: processLoop: Ring Buffer 消息到达. 消息长度={}, frameAccumulator.remaining()={}",
                //    length, frameAccumulator.remaining());
                if (frameAccumulator.remaining() >= length) {
                    // log.info("DF_ACCUM_DIAG: processLoop: frameAccumulator 写入前 position: {}, limit: {}",
                    //    frameAccumulator.position(), frameAccumulator.limit());
                    buffer.getBytes(index, frameAccumulator, length); // 修正：移除第三个参数 offset
                    // log.info("DF_ACCUM_DIAG: processLoop: frameAccumulator 写入后 position: {}, limit: {}",
                    //    frameAccumulator.position(), frameAccumulator.limit());
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
            // log.info("DF_ACCUM_DIAG: processLoop: Agent 退出条件满足。正在刷新剩余数据。");
            // 确保在 agent 退出前，所有剩余数据都被 flush
            flushRemainingRingBufferData(frameAccumulator);
            // log.info("DF_ACCUM_DIAG: processLoop: 刷新后 frameAccumulator.position()={}", frameAccumulator.position());
            if (frameAccumulator.position() == 0) {
                // log.info("DF_ACCUM_DIAG: processLoop: Agent 准备停止。");
                return 0; // 表示已完成所有工作，AgentRunner 可以停止此 Agent
            } else {
                // 如果刷新后依然有数据，可能是逻辑错误，或者需要更多轮询来清空
                // log.warn("DF_ACCUM_DIAG: processLoop: Agent 退出时 frameAccumulator 未清空，position={}",
                //    frameAccumulator.position());
                return 1; // 尝试再做一轮工作
            }
        }

        return workDone; // 返回完成的工作量
    }

    private void flushRemainingRingBufferData(ByteBuffer frameAccumulator) {
        final int bytesPerFullFrame = frameLength * audioFormat.getFrameSize();

        ringBuffer.read((msgTypeId, buffer, index, length) -> {
            if (msgTypeId == MSG_TYPE_ID) {
                // log.info(
                //    "DF_ACCUM_DIAG: flushRemainingRingBufferData: Ring Buffer 消息到达. 消息长度={}, frameAccumulator"
                //        + ".remaining()={}",
                //    length, frameAccumulator.remaining());
                if (frameAccumulator.remaining() >= length) {
                    // log.info(
                    //    "DF_ACCUM_DIAG: flushRemainingRingBufferData: frameAccumulator 写入前 position: {}, limit: {}",
                    //    frameAccumulator.position(), frameAccumulator.limit());
                    buffer.getBytes(index, frameAccumulator, length); // 修正 Agrona 的 getBytes 调用
                    // log.info(
                    //    "DF_ACCUM_DIAG: flushRemainingRingBufferData: frameAccumulator 写入后 position: {}, limit: {}",
                    //    frameAccumulator.position(), frameAccumulator.limit());
                } else {
                    // log.info(
                    //    "DF_ACCUM_DIAG: flushRemainingRingBufferData: frameAccumulator 空间不足，无法完全读取 Ring Buffer "
                    //        + "剩余消息。剩余消息长度:"
                    //        + " {}, frameAccumulator.remaining(): {}",
                    //    length, frameAccumulator.remaining());
                }
            }
        }, Integer.MAX_VALUE);
        // log.info(
        //    "DF_ACCUM_DIAG: flushRemainingRingBufferData: 从 Ring Buffer 读取所有剩余消息. frameAccumulator.position()={}",
        //    frameAccumulator.position());

        if (frameAccumulator.position() > 0) {
            // log.info("DF_ACCUM_DIAG: flushRemainingRingBufferData: 缓冲区存在数据。position={}, remaining={}",
            //    frameAccumulator.position(), frameAccumulator.remaining());
            frameAccumulator.flip();
            // log.info("DF_ACCUM_DIAG: flushRemainingRingBufferData: flip()后 position={}, limit={}",
            //    frameAccumulator.position(), frameAccumulator.limit());
            int currentBufferedBytes = frameAccumulator.remaining();
            // log.info("DF_ACCUM_DIAG: flushRemainingRingBufferData: 缓冲区剩余字节数: {}", currentBufferedBytes);

            if (currentBufferedBytes < bytesPerFullFrame) {
                frameAccumulator.position(currentBufferedBytes);
                frameAccumulator.limit(bytesPerFullFrame);
                for (int i = currentBufferedBytes; i < bytesPerFullFrame; i++) {
                    frameAccumulator.put((byte)0);
                }
                frameAccumulator.rewind();
                // log.info("DF_ACCUM_DIAG: 刷新并零填充 {} 字节以构成最终帧。", bytesPerFullFrame - currentBufferedBytes);
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
            // log.info("DF_ACCUM_DIAG: flushRemainingRingBufferData: 处理并提供一个完整帧。listenerOutputQueue.size()={}",
            //    listenerOutputQueue.size());

            frameAccumulator.clear(); // 确保 frameAccumulator 在处理完所有剩余数据后被完全清空
            // log.info("DF_ACCUM_DIAG: flushRemainingRingBufferData: clear()后 frameAccumulator.position()={}",
            //    frameAccumulator.position());
        }
    }

    private byte[] convertFloatsToBytes(float[] outputFloats, int bytesPerFullFrame) {
        ByteBuffer outputByteBuffer = ByteBuffer.allocate(bytesPerFullFrame);
        outputByteBuffer.order(audioFormat.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frameLength; i++) {
            short s = (short)(outputFloats[i] * 32768.0f);
            outputByteBuffer.putShort(s);
        }
        return outputByteBuffer.array();
    }
}
