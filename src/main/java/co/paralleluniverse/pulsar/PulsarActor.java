package co.paralleluniverse.pulsar;

import clojure.lang.IFn;
import co.paralleluniverse.actors.Actor;
import co.paralleluniverse.actors.MessageProcessor;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.SuspendableCallable;
import java.util.concurrent.TimeUnit;

/**
 * @author pron
 */
public class PulsarActor extends Actor<Object, Object> {
    public static final Object NO_MATCH = new Object();
    private final SuspendableCallable<Object> target;

    @SuppressWarnings("LeakingThisInConstructor")
    public PulsarActor(String name, int mailboxSize, SuspendableCallable<Object> target) {
        super(name, mailboxSize);
        this.target = target;
    }

    @Override
    public Object doRun() throws InterruptedException, SuspendExecution {
        return target.run();
    }

    @Override
    public Object receive() throws SuspendExecution, InterruptedException {
        return super.receive();
    }

    public Object receive(long timeout) throws SuspendExecution, InterruptedException {
        return super.receive(timeout, TimeUnit.MILLISECONDS);
    }

    public Object receive(final IFn fn) throws SuspendExecution, InterruptedException {
        return receive(0, fn);
    }

    public Object receive(long timeout, final IFn fn) throws SuspendExecution, InterruptedException {
        final Result result = new Result();
        super.receive(timeout, TimeUnit.MILLISECONDS, new MessageProcessor<Object>() {
            @Override
            public boolean process(Object msg) throws SuspendExecution, InterruptedException {
                Object res = fn.invoke(msg);
                result.res = res;
                return res != NO_MATCH;
            }
        });
        return result.res;
    }

    private static class Result {
        Object res;
    }
}
