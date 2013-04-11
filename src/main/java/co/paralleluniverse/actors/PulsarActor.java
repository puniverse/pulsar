package co.paralleluniverse.actors;

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.SuspendableCallable;
import java.util.concurrent.TimeUnit;
import jsr166e.ForkJoinPool;

/**
 * @author pron
 */
public class PulsarActor<Message, V> extends Actor<Message, V> {
    @SuppressWarnings("LeakingThisInConstructor")
    public PulsarActor(String name, ForkJoinPool fjPool, int stackSize, int mailboxSize, ActorTarget<V> target) {
        super(name, fjPool, stackSize, mailboxSize, wrap(target));
        ((ActorCallable) getTarget()).self = this;
    }

    @SuppressWarnings("LeakingThisInConstructor")
    public PulsarActor(String name, int stackSize, int mailboxSize, ActorTarget<V> target) {
        super(name, stackSize, mailboxSize, wrap(target));
        ((ActorCallable) getTarget()).self = this;
    }

    private static <V> SuspendableCallable<V> wrap(ActorTarget<V> target) {
        return new ActorCallable<V>(target);
    }

    private static class ActorCallable<V> implements SuspendableCallable<V> {
        private final ActorTarget<V> target;
        Actor self;

        public ActorCallable(ActorTarget<V> target) {
            this.target = target;
        }

        @Override
        public V run() throws SuspendExecution, InterruptedException {
            return target.run(self);
        }
    }

    @Override
    public V run() throws InterruptedException, SuspendExecution {
        return getTarget().run();
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
    public Message receive(long timeout, TimeUnit unit, Message currentMessage, MessageProcessor<Message> proc) throws SuspendExecution, InterruptedException {
        return super.receive(timeout, unit, currentMessage, proc);
    }

    @Override
    public Message receive(Message currentMessage, MessageProcessor<Message> proc) throws SuspendExecution, InterruptedException {
        return super.receive(currentMessage, proc);
    }

    @Override
    public Message receive(long timeout, TimeUnit unit, MessageProcessor<Message> proc) throws SuspendExecution, InterruptedException {
        return super.receive(timeout, unit, proc);
    }

    @Override
    public Message receive(MessageProcessor<Message> proc) throws SuspendExecution, InterruptedException {
        return super.receive(proc);
    }
}
