package co.paralleluniverse.pulsar;

import clojure.lang.IFn;
import clojure.lang.IObj;
import clojure.lang.Keyword;
import clojure.lang.PersistentVector;
import co.paralleluniverse.actors.Actor;
import co.paralleluniverse.actors.ExitMessage;
import co.paralleluniverse.actors.LifecycleMessage;
import co.paralleluniverse.actors.MessageProcessor;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.SuspendableCallable;
import java.util.concurrent.TimeUnit;

/**
 * @author pron
 */
public class PulsarActor extends Actor<Object, Object> {
    public static <Message> void send(Actor<Message, ?> actor, Message m) {
        actor.send(m);
    }

    public static <Message> void sendSync(Actor<Message, ?> actor, Message m) {
        actor.sendSync(m);
    }

    public static Object selfReceiveSimple() throws SuspendExecution, InterruptedException {
        return ((PulsarActor) currentActor()).receive();
    }

    public static Object selfReceiveSimple(long timeout) throws SuspendExecution, InterruptedException {
        return ((PulsarActor) currentActor()).receive(timeout);
    }

    public static Object selfReceive(IFn fn) throws SuspendExecution, InterruptedException {
        return ((PulsarActor) currentActor()).receive(fn);
    }

    public static Object selfReceive(long timeout, IFn fn) throws SuspendExecution, InterruptedException {
        return ((PulsarActor) currentActor()).receive(timeout, fn);
    }
    ///////////////////////////////////////////////////////////////
    public static final Object NO_MATCH = new Object();
    private final SuspendableCallable<Object> target;
    private IFn curMP; // current Message Processor
    private Object mpRetValue;

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
        try {
            curMP = fn;
            super.receive(timeout, TimeUnit.MILLISECONDS, new MessageProcessor<Object>() {
                @Override
                public boolean process(Object msg) throws SuspendExecution, InterruptedException {
                    mpRetValue = fn.invoke(msg);
                    return mpRetValue != NO_MATCH;
                }
            });
            return mpRetValue;
        } finally {
            curMP = null;
            mpRetValue = null;
        }
    }

    @Override
    protected void handleLifecycleMessage(LifecycleMessage msg) {
        super.handleLifecycleMessage(msg);
        if (curMP != null) {
            if (msg instanceof ExitMessage) {
                final ExitMessage m = (ExitMessage) msg;

                final IObj v = PersistentVector.create(keyword("exit"), m.monitor, m.actor, m.reason);
                mpRetValue = curMP.invoke(v);
                if (mpRetValue == NO_MATCH)
                    throw new RuntimeException("Unprocessed lifecycle message: " + v);
            }
        }
    }

    private static Keyword keyword(String s) {
        return Keyword.intern(s);
    }
}
