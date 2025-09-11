package source.hanger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;

import com.sun.jna.Pointer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DeepFilterNetStreamProcessor {

    private final DeepFilterNetNativeLib nativeLib;
    @Getter
    private final int frameLength;
    private final AudioFormat audioFormat;
    private final DfNativeLogThread logThread;
    private final BlockingQueue<byte[]> inputQueue; // 新增：用于接收外部传入的音频帧
    private final BlockingQueue<byte[]> denoisedOutputQueue;
    private final AtomicBoolean running = new AtomicBoolean(false); // 新增：控制内部处理线程的运行状态
    private final Thread processingThread; // 新增：内部处理线程
    private final ByteBuffer internalByteBuffer; // 用于 byte[] 到 float[] 转换的内部 ByteBuffer
    private final float[] internalInputFloats;   // 用于 byte[] 到 float[] 转换的内部 float[]
    private Pointer dfState;

    public DeepFilterNetStreamProcessor(String modelPath, float attenLim, String logLevel, AudioFormat audioFormat,
        BlockingQueue<byte[]> denoisedOutputQueue)
        throws RuntimeException {
        if (audioFormat.getChannels() != 1 || audioFormat.getSampleSizeInBits() != 16
            || audioFormat.getSampleRate() != 48000.0f) {
            throw new IllegalArgumentException(
                "不支持的音频格式。DeepFilterNet 仅支持单声道、16-bit PCM、48kHz 采样率的音频。");
        }
        this.audioFormat = audioFormat;
        this.denoisedOutputQueue = denoisedOutputQueue;
        this.inputQueue = new ArrayBlockingQueue<>(500); // 初始化输入队列

        // dfState 和 logThread 在构造函数中初始化
        this.nativeLib = DeepFilterNetLibraryInitializer.getNativeLibraryInstance();
        this.dfState = nativeLib.df_create(modelPath, attenLim, logLevel);
        if (dfState == Pointer.NULL) {
            log.error("无法创建 DeepFilterNet 模型。请检查模型路径或日志。");
            throw new RuntimeException("无法创建 DeepFilterNet 模型。请检查模型路径或日志。");
        }
        this.logThread = new DfNativeLogThread(dfState);
        this.logThread.start();
        this.frameLength = nativeLib.df_get_frame_length(dfState); // 使用有效的 dfState 获取 frameLength

        // 初始化内部转换缓冲区和数组
        this.internalByteBuffer = ByteBuffer.allocateDirect(frameLength * audioFormat.getFrameSize());
        this.internalByteBuffer.order(audioFormat.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        this.internalInputFloats = new float[frameLength];

        this.processingThread = new Thread(this::processLoop, "DeepFilterNetProcessingThread"); // 创建处理线程
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            processingThread.start();
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            // 清空输入队列，避免线程阻塞
            inputQueue.clear();
            processingThread.interrupt();
            try {
                processingThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("处理线程在停止时被中断: {}", e.getMessage());
            }
            release(); // 在处理线程结束时释放资源
        }
    }

    // 新增方法：通知 DeepFilterNetStreamProcessor 输入结束
    public void signalEndOfInput() {
        running.set(false);
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
                log.warn("日志线程在释放时被中断: {}", e.getMessage());
            }
        }
    }

    public boolean isRunning() {
        return running.get() || !inputQueue.isEmpty(); // 如果队列中还有数据，也认为是运行中
    }

    public boolean processAudioFrame(byte[] inputBytes) {
        try {
            // 如果处理线程未启动或已停止，则拒绝处理新的音频帧
            if (!running.get() && processingThread.getState() == Thread.State.NEW) {
                log.warn("DeepFilterNetStreamProcessor 未启动或已停止，无法处理音频帧。");
                return false;
            }
            return inputQueue.offer(inputBytes, 100, java.util.concurrent.TimeUnit.MILLISECONDS); // 将输入帧放入队列
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("输入队列中断: {}", e.getMessage());
            return false;
        }
    }

    private void processLoop() {
        try {
            while (running.get() || !inputQueue.isEmpty()) {
                byte[] inputBytes = inputQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (inputBytes != null) {
                    // 将 byte[] 转换为 float[]
                    internalByteBuffer.clear();
                    internalByteBuffer.put(inputBytes);
                    internalByteBuffer.flip();
                    for (int i = 0; i < frameLength; i++) {
                        internalInputFloats[i] = internalByteBuffer.getShort(i * 2) / 32768.0f;
                    }

                    float[] outputFloats = new float[frameLength];
                    nativeLib.df_process_frame(dfState, internalInputFloats, outputFloats);

                    ByteBuffer byteBuffer = ByteBuffer.allocate(frameLength * audioFormat.getFrameSize());
                    byteBuffer.order(audioFormat.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
                    byteBuffer.clear();
                    for (int i = 0; i < frameLength; i++) {
                        short s = (short)(outputFloats[i] * 32768.0f);
                        byteBuffer.putShort(s);
                    }
                    byte[] processedBytes = byteBuffer.array();

                    denoisedOutputQueue.offer(processedBytes, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("处理循环被中断: {}", e.getMessage());
        } catch (Exception e) {
            log.error("处理音频帧时发生错误: {}", e.getMessage(), e);
        } finally {
            release(); // 确保在处理线程结束时释放资源
            log.info("DeepFilterNetStreamProcessor 内部处理线程已停止。");
        }
    }
}
