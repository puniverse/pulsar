package co.paralleluniverse.pulsar;

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.channels.ReceivePort;
import co.paralleluniverse.strands.channels.SendPort;
import co.paralleluniverse.strands.channels.transfer.Pipeline;

/**
 * @author circlespainter
 */
public class IdentityPipeline<T> extends Pipeline<T, T> {
    public IdentityPipeline(ReceivePort<? extends T> from, SendPort<? super T> to, int parallelism, boolean closeTo) {
        super(from, to, parallelism, closeTo);
    }

    @Override
    public T transform(T t) throws SuspendExecution, InterruptedException {
        return t;
    }
}
