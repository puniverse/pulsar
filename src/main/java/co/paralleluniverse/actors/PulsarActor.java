package co.paralleluniverse.actors;

import clojure.lang.IObj;
import clojure.lang.Keyword;
import clojure.lang.PersistentVector;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.TimeoutException;
import co.paralleluniverse.strands.SuspendableCallable;
import co.paralleluniverse.strands.channels.Mailbox;
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

    public static Object selfReceive() throws SuspendExecution, InterruptedException {
        return ((PulsarActor) currentActor()).receive();
    }

    public static Object selfReceive(long timeout) throws SuspendExecution, InterruptedException {
        return ((PulsarActor) currentActor()).receive(timeout);
    }

    public static Object selfReceiveAll() throws SuspendExecution, InterruptedException {
        return ((PulsarActor) currentActor()).receiveAll();
    }

    public static Object selfReceiveAll(long timeout) throws SuspendExecution, InterruptedException {
        return ((PulsarActor) currentActor()).receiveAll(timeout);
    }
    
    public static PulsarActor currentActor() {
        return (PulsarActor)Actor.currentActor();
    }

    public static Mailbox<Object> selfMailbox() throws SuspendExecution, InterruptedException {
        return currentActor().mailbox();
    }
    ///////////////////////////////////////////////////////////////
    private final SuspendableCallable<Object> target;
    private boolean trap;

    @SuppressWarnings("LeakingThisInConstructor")
    public PulsarActor(String name, boolean trap, int mailboxSize, SuspendableCallable<Object> target) {
        super(name, mailboxSize);
        this.target = target;
        this.trap = trap;
    }

    public void setTrap(boolean trap) {
        this.trap = trap;
    }

    public boolean isTrap() {
        return trap;
    }

    @Override
    public Object doRun() throws InterruptedException, SuspendExecution {
        return target.run();
    }

    public Object receive(long timeout) throws SuspendExecution, InterruptedException {
        return super.receive(timeout, TimeUnit.MILLISECONDS);
    }

    public Object receiveAll() throws InterruptedException, SuspendExecution {
        if(!trap)
            return super.receive();
        record(1, "PulsarActor", "receiveAll", "%s waiting for a message", this);
        final Object m = convert(mailbox().receive());
        record(1, "PulsarActor", "receive", "Received %s <- %s", this, m);
        monitorAddMessage();
        return m;
    }

    public Object receiveAll(long timeout) throws InterruptedException, SuspendExecution {
        if(!trap) {
            return timeout == 0 ? super.tryReceive() : super.receive(timeout, TimeUnit.MILLISECONDS);
        }
        
        if (flightRecorder != null)
            record(1, "PulsarActor", "receiveAll", "%s waiting for a message. Millis left: %s", this, timeout);
        final Object m = convert(timeout == 0 ? mailbox().tryReceive() : mailbox().receive(timeout, TimeUnit.MILLISECONDS));
        if (m == null)
            record(1, "PulsarActor", "receiveAll", "%s timed out", this);
        else {
            record(1, "PulsarActor", "receiveAll", "Received %s <- %s", this, m);
            monitorAddMessage();
        }
        return m;
    }

    public void processed(Object n) {
        monitorAddMessage();
        mailbox().del(n);
    }
    
    public void skipped(Object n) {
        monitorSkippedMessage();
        final Object m = mailbox().value(n);
        if(m instanceof LifecycleMessage)
            handleLifecycleMessage((LifecycleMessage)m);
    }

    @Override
    public void handleLifecycleMessage(LifecycleMessage m) {
        super.handleLifecycleMessage(m);
    }
    
    public static Object convert(Object m) {
        if (m == null)
            return null;
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
    
    ///////////////// Simple delegates ////////////////////////////
    
    public Object succ(Object n) {
        if(n == null)
            monitorResetSkippedMessages();
        return mailbox().succ(n);
    }

    public Object value(Object n) {
        final Object m = mailbox().value(n);
        record(1, "PulsarActor", "receive", "Received %s <- %s", this, m);
        return m;
    }

    public void maybeSetCurrentStrandAsOwner() {
        mailbox().maybeSetCurrentStrandAsOwner();
    }

    public void lock() {
        mailbox().lock();
    }

    public void unlock() {
        mailbox().unlock();
    }

    public void await() throws SuspendExecution, InterruptedException {
        record(1, "PulsarActor", "receive", "%s waiting for a message", this);
        mailbox().await();
    }

    public void await(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        if (flightRecorder != null)
            record(1, "PulsarActor", "receive", "%s waiting for a message.Millis left: %s ", this, TimeUnit.MILLISECONDS.convert(timeout, unit));
        mailbox().await(timeout, unit);
    }

    public void timeout() {
        record(1, "PulsarActor", "receive", "%s timed out", this);
        throw new TimeoutException();
    }
}
