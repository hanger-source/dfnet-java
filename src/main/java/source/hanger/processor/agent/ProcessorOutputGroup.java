package source.hanger.processor.agent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import source.hanger.util.AudioFrameListener;

public record ProcessorOutputGroup(
    String processorId,
    AudioFrameListener denoisedFrameListener,
    OneToOneConcurrentArrayQueue<byte[]> listenerOutputQueue,
    AtomicBoolean endOfInputSignaled,
    Supplier<Integer> inputSizeSupplier
) {
}
