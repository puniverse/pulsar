package co.paralleluniverse.actors;

import clojure.lang.IFn;
import clojure.lang.IObj;
import clojure.lang.Keyword;
import clojure.lang.PersistentVector;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.SuspendableCallable;
import co.paralleluniverse.strands.channels.Mailbox;
import java.util.concurrent.TimeUnit;

/**
 * @author pron
 */
public class PulsarActor extends SimpleActor<Object, Object> {
    public static <Message> void send(Actor<Message, ?> actor, Message m) {
        actor.send(m);
    }

    public static <Message> void sendSync(Actor<Message, ?> actor, Message m) {
        actor.sendSync(m);
    }

    public static Object selfReceive() throws SuspendExecution, InterruptedException {
        return ((PulsarActor) currentActor()).receive();
    }

    public static Object selfReceive(long timeout) throws SuspendExecution, InterruptedException {
        return ((PulsarActor) currentActor()).receive(timeout);
    }

    public static Mailbox<Object> selfMailbox() throws SuspendExecution, InterruptedException {
        return currentActor().mailbox();
    }


    ///////////////////////////////////////////////////////////////
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

    public Object receiveAll() throws InterruptedException, SuspendExecution {
        Object m = mailbox().receive();
        if (m instanceof LifecycleMessage)
            return lifecycleMessageToClojure((LifecycleMessage) m);
        else
            return m;
    }

    public static Object convert(Object m) {
        if (m instanceof LifecycleMessage)
            return lifecycleMessageToClojure((LifecycleMessage) m);
        else
            return m;
    }

    public static IObj lifecycleMessageToClojure(LifecycleMessage msg) {
        if (msg instanceof ExitMessage) {
            final ExitMessage m = (ExitMessage) msg;
            final IObj v = PersistentVector.create(keyword("exit"), m.monitor, m.actor, m.reason);
            return v;
        }
        throw new RuntimeException("Unknown lifecycle message: " + msg);
    }

    private static Keyword keyword(String s) {
        return Keyword.intern(s);
    }
}
