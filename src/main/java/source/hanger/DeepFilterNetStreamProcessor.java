package source.hanger;

import com.sun.jna.Pointer;
import javax.sound.sampled.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class DeepFilterNetStreamProcessor {

    private DeepFilterNetNativeLib nativeLib;
    private Pointer dfState;
    private int frameLength;
    private AudioFormat audioFormat;
    private DfNativeLogThread logThread;
    private String logLevel;

    private TargetDataLine targetDataLine;
    private final BlockingQueue<byte[]> denoisedOutputQueue;
    private AtomicBoolean running = new AtomicBoolean(false);
    private Thread captureThread;
    private boolean isExternalInput;

    public DeepFilterNetStreamProcessor(String modelPath, float attenLim, String logLevel, AudioFormat audioFormat,
                                        BlockingQueue<byte[]> denoisedOutputQueue,
                                        boolean isExternalInput)
            throws LineUnavailableException, RuntimeException {
        if (audioFormat.getChannels() != 1 || audioFormat.getSampleSizeInBits() != 16 || audioFormat.getSampleRate() != 48000.0f) {
            throw new IllegalArgumentException("不支持的音频格式。DeepFilterNet 仅支持单声道、16-bit PCM、48kHz 采样率的音频。");
        }
        this.audioFormat = audioFormat;
        this.denoisedOutputQueue = denoisedOutputQueue;
        this.logLevel = logLevel;
        this.isExternalInput = isExternalInput;

        this.nativeLib = DeepFilterNetLibraryInitializer.getNativeLibraryInstance();
        this.dfState = nativeLib.df_create(modelPath, attenLim, logLevel);
        if (dfState == Pointer.NULL) {
            throw new RuntimeException("无法创建 DeepFilterNet 模型。请检查模型路径或日志。");
        }
        this.logThread = new DfNativeLogThread(dfState);
        this.logThread.start();
        this.frameLength = nativeLib.df_get_frame_length(dfState);

        if (!isExternalInput) {
            DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, audioFormat);
            if (!AudioSystem.isLineSupported(targetInfo)) {
                throw new LineUnavailableException("麦克风输入不支持此音频格式。");
            }
            targetDataLine = (TargetDataLine) AudioSystem.getLine(targetInfo);
        }
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
        float[] floatBuffer = new float[frameLength];
        ByteBuffer byteBuffer = ByteBuffer.allocate(frameLength * bytesPerFrame);
        byteBuffer.order(audioFormat.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
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
                } else if (bytesRead == -1) {
                    break;
                }
            } catch (Exception e) {
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
            }
        }
    }

    public int getFrameLength() {
        return frameLength;
    }

    public boolean processAudioFrame(float[] inputFloats) {
        float[] outputFloats = new float[frameLength];
        nativeLib.df_process_frame(dfState, inputFloats, outputFloats);

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
            return false; // Return false if interrupted
        }
    }
}
