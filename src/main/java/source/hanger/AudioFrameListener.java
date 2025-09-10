package source.hanger;

/**
 * `AudioFrameListener` 接口定义了在音频流处理过程中，用于监听原始和处理后音频帧的回调方法。
 * 实现此接口的类可以接收并处理这些音频帧，例如将它们写入文件或进行可视化。
 */
public interface AudioFrameListener {
    /**
     * 当捕获到原始音频帧时调用此方法。
     *
     * @param audioBytes 原始音频帧的字节数组。
     * @param offset 字节数组中数据的起始偏移量。
     * @param length 字节数组中数据的长度。
     */
    void onOriginalAudioFrame(byte[] audioBytes, int offset, int length);

    /**
     * 当生成降噪后的音频帧时调用此方法。
     *
     * @param audioBytes 降噪后音频帧的字节数组。
     * @param offset 字节数组中数据的起始偏移量。
     * @param length 字节数组中数据的长度。
     */
    void onDenoisedAudioFrame(byte[] audioBytes, int offset, int length);
}
