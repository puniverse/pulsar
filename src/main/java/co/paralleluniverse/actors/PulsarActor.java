/*
 * Pulsar: lightweight threads and Erlang-like actors for Clojure.
 * Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.actors;

import clojure.lang.IFn;
import clojure.lang.IObj;
import clojure.lang.Keyword;
import clojure.lang.PersistentVector;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.pulsar.ClojureHelper;
import co.paralleluniverse.strands.SuspendableCallable;
import co.paralleluniverse.strands.channels.Mailbox;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author pron
 */
public class PulsarActor extends LocalActor<Object, Object> {
    public static void send(Actor actor, Object m) {
        actor.send(m);
    }

    public static void sendSync(Actor actor, Object m) {
        actor.sendSync(m);
    }

    public static PulsarActor self() {
        final PulsarActor self = (PulsarActor) LocalActor.currentActor();
        if (self == null)
            throw new RuntimeException("Not running within an actor");
        return self;
    }

    public static Mailbox selfMailbox() {
        final PulsarActor self = (PulsarActor) LocalActor.currentActor();
        if (self == null)
            throw new RuntimeException("Not running within an actor");
        return self.mailbox();
    }

    public static Object selfReceive() throws SuspendExecution, InterruptedException {
        return self().receive();
    }

    public static Object selfReceive(long timeout) throws SuspendExecution, InterruptedException {
        return self().receive(timeout);
    }

    public static Object selfGetState() {
        return self().getState();
    }

    public static Object selfSetState(Object newState) {
        self().setState(newState);
        return newState;
    }
    ///////////////////////////////////////////////////////////////
    private final SuspendableCallable<Object> target;
    private final IFn lifecycleMessageHandler;
    private boolean trap;
    private Object state;

    @SuppressWarnings("LeakingThisInConstructor")
    public PulsarActor(String name, boolean trap, int mailboxSize, IFn lifecycleMessageHandler, IFn target) {
        super(name, mailboxSize);
        this.target = ClojureHelper.asSuspendableCallable(target);
        this.trap = trap;
        this.lifecycleMessageHandler = lifecycleMessageHandler;
    }

    public void setTrap(boolean trap) {
        this.trap = trap;
    }

    public boolean isTrap() {
        return trap;
    }

    private Object getState() {
        return state;
    }

    private void setState(Object state) {
        this.state = state;
    }

    @Override
    public Object doRun() throws InterruptedException, SuspendExecution {
        return target.run();
    }

    @Override
    protected Object filterMessage(Object m) {
        if(!trap && !(m instanceof ShutdownMessage))
            m = super.filterMessage(m);
        return convert(m);
    }

    public Object receive(long timeout) throws SuspendExecution, InterruptedException {
        return receive(timeout, TimeUnit.MILLISECONDS);
    }

    public void processed(Object n) {
        monitorAddMessage();
        mailbox().del(n);
    }

    public void skipped(Object n) {
        monitorSkippedMessage();
        final Object m = mailbox().value(n);
        if (m instanceof LifecycleMessage)
            handleLifecycleMessage((LifecycleMessage) m);
    }

    @Override
    public void handleLifecycleMessage(LifecycleMessage m) {
        if (lifecycleMessageHandler != null)
            lifecycleMessageHandler.invoke(lifecycleMessageToClojure(m));
        else
            super.handleLifecycleMessage(m);
    }

    @Override
    public Mailbox<Object> mailbox() {
        return super.mailbox();
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
            final IObj v = PersistentVector.create(keyword("exit"), m.watch, m.actor, m.cause);
            return v;
        } else if(msg instanceof ShutdownMessage) {
            final ShutdownMessage m = (ShutdownMessage) msg;
            final IObj v = PersistentVector.create(keyword("shutdown"), m.requester);
            return v;
        }
        throw new RuntimeException("Unknown lifecycle message: " + msg);
    }

    private static Keyword keyword(String s) {
        return Keyword.intern(s);
    }
    
    ///////////////// Simple delegates ////////////////////////////

    public Object succ(Object n) {
        if (n == null)
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

    public void timeout() throws TimeoutException {
        record(1, "PulsarActor", "receive", "%s timed out", this);
        throw new TimeoutException();
    }
}
