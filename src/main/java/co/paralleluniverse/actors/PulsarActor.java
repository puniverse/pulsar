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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author pron
 */
public class PulsarActor extends Actor<Object, Object> {
    public static void send(ActorRef actor, Object m) throws SuspendExecution {
        actor.send(m);
    }

    public static void sendSync(ActorRef actor, Object m) throws SuspendExecution {
        actor.sendSync(m);
    }

    public static PulsarActor currentActor() {
        final PulsarActor ca = (PulsarActor)Actor.currentActor();
        if (ca == null)
            throw new RuntimeException("Not running within an actor");
        return ca;
    }

    public static Mailbox selfMailbox() {
        return Actor.currentActor().mailbox();
    }

    public static Object selfReceive() throws SuspendExecution, InterruptedException {
        return Actor.currentActor().receive();
    }

    public static Object selfReceive(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        return currentActor().receive(timeout, unit);
    }

    public static Object selfGetState() {
        return Actor.currentActor().getAux();
    }

    public static Object selfSetState(Object newState) {
        Actor.currentActor().setAux(newState);
        return newState;
    }
    ///////////////////////////////////////////////////////////////
    private final SuspendableCallable<Object> target;
    private final IFn lifecycleMessageHandler;
    private boolean trap;

    @SuppressWarnings("LeakingThisInConstructor")
    public PulsarActor(String name, boolean trap, MailboxConfig mailboxConfig, IFn lifecycleMessageHandler, IFn target) {
        super(name, mailboxConfig);
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

    @Override
    public Object handleLifecycleMessage(LifecycleMessage m) {
        if (lifecycleMessageHandler != null)
            return lifecycleMessageHandler.invoke(lifecycleMessageToClojure(m));
        else
            super.handleLifecycleMessage(m);
        return null;
    }
    
    public static Object convert(Object m) {
        if (m == null)
            return null;
        if (m instanceof LifecycleMessage)
            return lifecycleMessageToClojure((LifecycleMessage) m);
        else
            return m;
    }

    private static IObj lifecycleMessageToClojure(LifecycleMessage msg) {
        if (msg instanceof ExitMessage) {
            final ExitMessage m = (ExitMessage) msg;
            return PersistentVector.create(keyword("exit"), m.watch, m.actor, m.cause);
        } else if(msg instanceof ShutdownMessage) {
            final ShutdownMessage m = (ShutdownMessage) msg;
            return PersistentVector.create(keyword("shutdown"), m.requester);
        }
        throw new RuntimeException("Unknown lifecycle message: " + msg);
    }

    private static Keyword keyword(String s) {
        return Keyword.intern(s);
    }
    
    ///////////////// Simple delegates ////////////////////////////


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

    public void await(int iter) throws SuspendExecution, InterruptedException {
        record(1, "PulsarActor", "receive", "%s waiting for a message", this);
        mailbox().await(iter);
    }

    public void await(int iter, long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        if (flightRecorder != null)
            record(1, "PulsarActor", "receive", "%s waiting for a message.Millis left: %s ", this, TimeUnit.MILLISECONDS.convert(timeout, unit));
        mailbox().await(iter, timeout, unit);
    }

    public void timeout() throws TimeoutException {
        record(1, "PulsarActor", "receive", "%s timed out", this);
        throw new TimeoutException();
    }
}
