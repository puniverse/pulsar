package co.paralleluniverse.actors;

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.SuspendableCallable;
import java.util.concurrent.TimeUnit;

/**
 * @author pron
 */
public class PulsarActor<Message, V> extends Actor<Message, V> {
    private final SuspendableCallable<V> target;
    
    @SuppressWarnings("LeakingThisInConstructor")
    public PulsarActor(String name, int mailboxSize, SuspendableCallable<V> target) {
        super(name, mailboxSize);
        this.target = target;
    }

    @Override
    public V doRun() throws InterruptedException, SuspendExecution {
        return target.run();
    }

    @Override
    public Message receive() throws SuspendExecution, InterruptedException {
        return super.receive();
    }

    @Override
    public Message receive(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        return super.receive(timeout, unit);
    }

    @Override
    public Message receive(MessageProcessor<Message> proc) throws SuspendExecution, InterruptedException {
        return super.receive(proc);
    }

    @Override
    public Message receive(long timeout, TimeUnit unit, MessageProcessor<Message> proc) throws SuspendExecution, InterruptedException {
        return super.receive(timeout, unit, proc);
    }
}
