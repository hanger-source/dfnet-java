package source.hanger;

import com.sun.jna.Pointer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class DeepFilterNetStreamProcessor {

    private final DeepFilterNetNativeLib nativeLib;
    private Pointer dfState;
    @Getter
    private final int frameLength;
    private final AudioFormat audioFormat;
    private final DfNativeLogThread logThread;

    private TargetDataLine targetDataLine;
    private final BlockingQueue<byte[]> denoisedOutputQueue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread captureThread;
    private final boolean isExternalInput;

    private final ByteBuffer internalByteBuffer; // 用于 byte[] 到 float[] 转换的内部 ByteBuffer
    private final float[] internalInputFloats;   // 用于 byte[] 到 float[] 转换的内部 float[]

    public DeepFilterNetStreamProcessor(String modelPath, float attenLim, String logLevel, AudioFormat audioFormat,
                                        BlockingQueue<byte[]> denoisedOutputQueue,
                                        boolean isExternalInput)
            throws LineUnavailableException, RuntimeException {
        if (audioFormat.getChannels() != 1 || audioFormat.getSampleSizeInBits() != 16 || audioFormat.getSampleRate() != 48000.0f) {
            throw new IllegalArgumentException("不支持的音频格式。DeepFilterNet 仅支持单声道、16-bit PCM、48kHz 采样率的音频。");
        }
        this.audioFormat = audioFormat;
        this.denoisedOutputQueue = denoisedOutputQueue;
        this.isExternalInput = isExternalInput;

        this.nativeLib = DeepFilterNetLibraryInitializer.getNativeLibraryInstance();
        this.dfState = nativeLib.df_create(modelPath, attenLim, logLevel);
        if (dfState == Pointer.NULL) {
            log.error("无法创建 DeepFilterNet 模型。请检查模型路径或日志。");
            throw new RuntimeException("无法创建 DeepFilterNet 模型。请检查模型路径或日志。");
        }
        this.logThread = new DfNativeLogThread(dfState);
        this.logThread.start();
        this.frameLength = nativeLib.df_get_frame_length(dfState);

        if (!isExternalInput) {
            DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, audioFormat);
            if (!AudioSystem.isLineSupported(targetInfo)) {
                log.error("麦克风输入不支持此音频格式: {}.", audioFormat);
                throw new LineUnavailableException("麦克风输入不支持此音频格式。");
            }
            targetDataLine = (TargetDataLine) AudioSystem.getLine(targetInfo);
        }

        // 初始化内部转换缓冲区和数组
        this.internalByteBuffer = ByteBuffer.allocateDirect(frameLength * audioFormat.getFrameSize());
        this.internalByteBuffer.order(audioFormat.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        this.internalInputFloats = new float[frameLength];
    }

    public void start() throws LineUnavailableException {
        if (running.compareAndSet(false, true)) {
            if (!isExternalInput) {
                targetDataLine.open(audioFormat, frameLength * audioFormat.getFrameSize() * 4);
                targetDataLine.start();
            }
            if (!isExternalInput) {
                captureThread = new Thread(this::captureAudio, "AudioCaptureThread");
                captureThread.start();
            }
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (targetDataLine != null) {
                targetDataLine.stop();
                targetDataLine.close();
            }
            if (captureThread != null) captureThread.interrupt();
            try {
                if (captureThread != null) captureThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("捕获线程在停止时被中断: {}", e.getMessage());
            }
            release();
        }
    }

    private void captureAudio() {
        if (isExternalInput) {
            return;
        }
        int bytesPerFrame = audioFormat.getFrameSize();
        byte[] buffer = new byte[frameLength * bytesPerFrame];
        ByteBuffer byteBuffer = ByteBuffer.allocate(frameLength * bytesPerFrame);
        byteBuffer.order(audioFormat.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        float[] floatBuffer = new float[frameLength]; // 此处 floatBuffer 仅用于 captureAudio 内部
        while (running.get()) {
            try {
                int bytesRead = targetDataLine.read(buffer, 0, buffer.length);
                if (bytesRead == buffer.length) {
                    byteBuffer.clear();
                    byteBuffer.put(buffer);
                    byteBuffer.flip();
                    for (int i = 0; i < frameLength; i++) {
                        floatBuffer[i] = byteBuffer.getShort(i * 2) / 32768.0f;
                    }
                    // 暂时不做任何处理，因为 DeepFilterNetStreamProcessor 是同步处理
                } else if (bytesRead == -1) {
                    break;
                }
            } catch (Exception e) {
                log.error("捕获音频时发生错误: {}", e.getMessage(), e);
            }
        }
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

    public boolean processAudioFrame(byte[] inputBytes) {
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
            short s = (short) (outputFloats[i] * 32768.0f);
            byteBuffer.putShort(s);
        }
        byte[] processedBytes = byteBuffer.array();

        try {
            return denoisedOutputQueue.offer(processedBytes, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("降噪输出队列中断: {}", e.getMessage());
            return false;
        }
    }
}
