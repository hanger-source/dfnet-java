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

    private TargetDataLine targetDataLine;
    private SourceDataLine sourceDataLine;

    private AudioFrameListener audioFrameListener;

    private BlockingQueue<float[]> inputQueue;
    private BlockingQueue<float[]> outputQueue;

    private AtomicBoolean running = new AtomicBoolean(false);

    private Thread captureThread;
    private Thread processThread;
    private Thread playbackThread;

    /**
     * DeepFilterNetStreamProcessor 构造函数，初始化模型和音频I/O。
     *
     * @param modelPath DeepFilterNet ONNX 模型文件路径 (.tar.gz)。
     * @param attenLim 衰减限制 (dB)。
     * @param logLevel 日志级别 (例如 "info", "debug")。
     * @param audioFormat 音频格式，必须是单声道、16-bit PCM、48kHz。
     * @param listener 音频帧监听器 (可为null)。
     * @throws LineUnavailableException 如果无法获取音频输入/输出行。
     * @throws RuntimeException 如果模型无法创建或文件不存在，或者音频格式不支持。
     */
    public DeepFilterNetStreamProcessor(String modelPath, float attenLim, String logLevel, AudioFormat audioFormat,
                                        AudioFrameListener listener)
            throws LineUnavailableException, RuntimeException {

        // 验证音频格式
        if (audioFormat.getChannels() != 1 || audioFormat.getSampleSizeInBits() != 16 || audioFormat.getSampleRate() != 48000.0f) {
            throw new IllegalArgumentException("DF_ERROR: 不支持的音频格式。DeepFilterNet 仅支持单声道、16-bit PCM、48kHz 采样率的音频。");
        }
        this.audioFormat = audioFormat;
        this.audioFrameListener = listener;

        // 获取 DeepFilterNetNativeLib 实例
        this.nativeLib = DeepFilterNetLibraryInitializer.getNativeLibraryInstance();

        // 1. 创建 DeepFilterNet 模型实例
        this.dfState = nativeLib.df_create(modelPath, attenLim, logLevel);
        if (dfState == Pointer.NULL) {
            throw new RuntimeException("DF_ERROR: 无法创建 DeepFilterNet 模型。请检查模型路径或日志。");
        }
        System.out.println("DF_LOG: DeepFilterNet Stream Processor 模型创建成功。");

        // 启动日志线程
        this.logThread = new DfNativeLogThread(dfState);
        this.logThread.start();

        // 2. 获取 DeepFilterNet 期望的帧长度
        this.frameLength = nativeLib.df_get_frame_length(dfState);
        System.out.println("DF_LOG: DeepFilterNet 期望的帧长度 (样本数): " + frameLength);

        // 初始化音频输入/输出
        DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, audioFormat);
        if (!AudioSystem.isLineSupported(targetInfo)) {
            throw new LineUnavailableException("DF_ERROR: 麦克风输入不支持此音频格式。");
        }
        targetDataLine = (TargetDataLine) AudioSystem.getLine(targetInfo);

        DataLine.Info sourceInfo = new DataLine.Info(SourceDataLine.class, audioFormat);
        if (!AudioSystem.isLineSupported(sourceInfo)) {
            throw new LineUnavailableException("DF_ERROR: 扬声器输出不支持此音频格式。");
        }
        sourceDataLine = (SourceDataLine) AudioSystem.getLine(sourceInfo);

        // 初始化队列
        int queueCapacity = 10; // 适当的缓冲区大小
        inputQueue = new ArrayBlockingQueue<>(queueCapacity);
        outputQueue = new ArrayBlockingQueue<>(queueCapacity);
    }

    /**
     * 启动实时音频降噪处理。
     *
     * @throws LineUnavailableException 如果音频输入/输出行无法打开。
     */
    public void start() throws LineUnavailableException {
        if (running.compareAndSet(false, true)) {
            targetDataLine.open(audioFormat, frameLength * audioFormat.getFrameSize() * 2); // 缓冲区大小，至少两帧
            sourceDataLine.open(audioFormat, frameLength * audioFormat.getFrameSize() * 2); // 缓冲区大小，至少两帧

            targetDataLine.start();
            sourceDataLine.start();
            System.out.println("DF_LOG: DeepFilterNet Stream Processor 已启动。");

            captureThread = new Thread(this::captureAudio, "AudioCaptureThread");
            processThread = new Thread(this::processAudio, "AudioProcessThread");
            playbackThread = new Thread(this::playbackAudio, "AudioPlaybackThread");

            captureThread.start();
            processThread.start();
            playbackThread.start();
        }
    }

    /**
     * 停止实时音频降噪处理并释放资源。
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            System.out.println("DF_LOG: DeepFilterNet Stream Processor 正在停止...");

            // 停止数据线
            if (targetDataLine != null) {
                targetDataLine.stop();
                targetDataLine.close();
            }
            if (sourceDataLine != null) {
                sourceDataLine.stop();
                sourceDataLine.close();
            }

            // 中断线程
            if (captureThread != null) captureThread.interrupt();
            if (processThread != null) processThread.interrupt();
            if (playbackThread != null) playbackThread.interrupt();

            // 确保线程结束
            try {
                if (captureThread != null) captureThread.join();
                if (processThread != null) processThread.join();
                if (playbackThread != null) playbackThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("DF_LOG_ERROR: 等待流处理线程结束时被中断。");
            }

            // 释放 DeepFilterNet 模型资源
            release();
            System.out.println("DF_LOG: DeepFilterNet Stream Processor 已停止。");
        }
    }

    /**
     * 捕获音频的线程逻辑。
     */
    private void captureAudio() {
        int bytesPerFrame = audioFormat.getFrameSize();
        byte[] buffer = new byte[frameLength * bytesPerFrame];
        float[] floatBuffer = new float[frameLength];
        ByteBuffer byteBuffer = ByteBuffer.allocate(frameLength * 4); // 4 bytes per float
        byteBuffer.order(audioFormat.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

        while (running.get()) {
            try {
                int bytesRead = targetDataLine.read(buffer, 0, buffer.length);
                if (bytesRead == buffer.length) {
                    // 将原始音频数据报告给监听器 (如果存在)
                    if (audioFrameListener != null) {
                        audioFrameListener.onOriginalAudioFrame(buffer, 0, bytesRead);
                        System.out.println(String.format("DF_TRACE: original frame captured, bytes: %d, first 4 bytes: %02X %02X %02X %02X", bytesRead, buffer[0], buffer[1], buffer[2], buffer[3]));
                    }

                    // Convert byte[] to float[]
                    byteBuffer.clear();
                    byteBuffer.put(buffer);
                    byteBuffer.flip();
                    for (int i = 0; i < frameLength; i++) {
                        floatBuffer[i] = byteBuffer.getShort(i * 2) / 32768.0f; // 16-bit PCM to float
                    }
                    inputQueue.put(floatBuffer); // Put into input queue
                } else if (bytesRead > 0) {
                    // Not a full frame, can be problematic for fixed frame processing.
                    // For simplicity, we only process full frames.
                    System.err.println("DF_WARNING: 捕获到不足一帧的数据，已忽略。");
                } else if (bytesRead == -1) {
                    System.out.println("DF_TRACE: End of audio stream reached in captureAudio.");
                    break; // End of stream
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("DF_TRACE: captureAudio thread interrupted.");
                break;
            } catch (Exception e) {
                System.err.println("DF_LOG_ERROR: 音频捕获过程中发生错误: " + e.getMessage());
            }
        }
        System.out.println("DF_TRACE: captureAudio thread stopped.");
    }

    /**
     * 处理音频的线程逻辑。
     */
    private void processAudio() {
        float[] inputFloats = new float[frameLength];
        float[] outputFloats = new float[frameLength];
        int frameCount = 0; // for tracing

        while (running.get()) {
            try {
                inputFloats = inputQueue.take(); // Take from input queue
                frameCount++;
                // Process the audio frame
                nativeLib.df_process_frame(dfState, inputFloats, outputFloats);
                System.out.println(String.format("DF_TRACE: frame processed, frame count: %d, first float: %.4f", frameCount, outputFloats[0]));
                outputQueue.put(outputFloats); // Put into output queue
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("DF_TRACE: processAudio thread interrupted.");
                break;
            } catch (Exception e) {
                System.err.println("DF_LOG_ERROR: 音频处理过程中发生错误: " + e.getMessage());
            }
        }
        System.out.println("DF_TRACE: processAudio thread stopped.");
    }

    /**
     * 播放音频的线程逻辑。
     */
    private void playbackAudio() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(frameLength * audioFormat.getFrameSize()); // 修正 ByteBuffer 分配大小
        byteBuffer.order(audioFormat.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        int frameCount = 0; // for tracing

        while (running.get()) {
            try {
                float[] outputFloats = outputQueue.take(); // Take from output queue
                frameCount++;
                // Convert float[] to byte[] (16-bit PCM)
                byteBuffer.clear();
                for (int i = 0; i < frameLength; i++) {
                    short s = (short) (outputFloats[i] * 32768.0f);
                    byteBuffer.putShort(s);
                }
                byte[] processedBytes = byteBuffer.array(); // 获取降噪后的字节数组
                int bytesToWrite = frameLength * audioFormat.getFrameSize(); // 修正实际写入的字节数
                sourceDataLine.write(processedBytes, 0, bytesToWrite); // Write to speaker
                System.out.println(String.format("DF_TRACE: denoised frame played, frame count: %d, bytes: %d, first 4 bytes: %02X %02X %02X %02X", frameCount, bytesToWrite, processedBytes[0], processedBytes[1], processedBytes[2], processedBytes[3]));

                // 将降噪后的音频数据报告给监听器 (如果存在)
                if (audioFrameListener != null) {
                    audioFrameListener.onDenoisedAudioFrame(processedBytes, 0, bytesToWrite); // 修正传递给监听器的字节数
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("DF_TRACE: playbackAudio thread interrupted.");
                break;
            } catch (Exception e) {
                System.err.println("DF_LOG_ERROR: 音频播放过程中发生错误: " + e.getMessage());
            }
        }
        System.out.println("DF_TRACE: playbackAudio thread stopped.");
    }

    /**
     * 释放 DeepFilterNet 模型资源。
     */
    public void release() {
        if (dfState != Pointer.NULL) {
            nativeLib.df_free(dfState);
            dfState = Pointer.NULL;
            System.out.println("DF_LOG: DeepFilterNet 模型资源已释放。");
        }
        if (logThread != null) {
            logThread.stopLogging();
            try {
                logThread.join(); // 等待日志线程结束
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("DF_LOG_ERROR: 等待日志线程结束时被中断。");
            }
        }
    }
}
