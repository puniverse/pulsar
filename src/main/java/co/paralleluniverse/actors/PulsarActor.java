/*
 * Pulsar: lightweight threads and Erlang-like actors for Clojure.
 * Copyright (C) 2013-2014, Parallel Universe Software Co. All rights reserved.
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

import clojure.lang.*;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.pulsar.ClojureHelper;
import co.paralleluniverse.pulsar.InstrumentedIFn;
import co.paralleluniverse.strands.SuspendableCallable;
import co.paralleluniverse.strands.queues.QueueIterator;

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

    public static Actor currentActor() {
        final Actor ca = Actor.currentActor();
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
    //private final Var var;
    private IFn targetFn;
    private SuspendableCallable<Object> target;
    private final IFn lifecycleMessageHandler;
    private boolean trap;

    @SuppressWarnings("LeakingThisInConstructor")
    public PulsarActor(String name, IFn targetFn, boolean trap, MailboxConfig mailboxConfig, IFn lifecycleMessageHandler, IFn target) {
        super(name, mailboxConfig);
        //this.var = var;
        this.targetFn = targetFn instanceof InstrumentedIFn ? ((InstrumentedIFn)targetFn).fn : targetFn;
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

    public boolean isTargetChanged(IFn targetFn) {
        targetFn = targetFn instanceof InstrumentedIFn ? ((InstrumentedIFn)targetFn).fn : targetFn;
        return this.targetFn != targetFn;
    }

    public void recurCodeSwap(IFn targetFn, IFn target) {
        targetFn = targetFn instanceof InstrumentedIFn ? ((InstrumentedIFn)targetFn).fn : targetFn;
        if(this.targetFn != targetFn) {
            this.targetFn = targetFn;
            this.target = ClojureHelper.asSuspendableCallable(target);
            throw CodeSwap.CODE_SWAP;
        }
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


    public static QueueIterator<Object> iterator(Actor a) {
        a.monitorResetSkippedMessages();
        return a.mailbox().queue().iterator();
    }

    public static void processed(Actor a, QueueIterator<Object> it) {
        a.monitorAddMessage();
        it.remove();
    }

    public static void skipped(Actor a, QueueIterator<Object> it) {
        a.monitorSkippedMessage();
        final Object m = it.value();
        if (m instanceof LifecycleMessage)
            handleLifecycleMessage(a, (LifecycleMessage) m);
    }

    public static Object next(Actor a, QueueIterator<Object> it) {
        final Object m = it.next();
        a.record(1, "PulsarActor", "receive", "Received %s <- %s", a, m);
        return m;
    }

    public static void handleLifecycleMessage(Actor a, LifecycleMessage m) {
        a.handleLifecycleMessage(m);
    }


    public static void maybeSetCurrentStrandAsOwner(Actor a) {
        a.mailbox().maybeSetCurrentStrandAsOwner();
    }

    public static void lock(Actor a) {
        a.mailbox().lock();
    }

    public static void unlock(Actor a) {
        a.mailbox().unlock();
    }

    public static void await(Actor a, int iter) throws SuspendExecution, InterruptedException {
        a.record(1, "PulsarActor", "receive", "%s waiting for a message", a);
        a.mailbox().await(iter);
    }

    public static void await(Actor a, int iter, long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        if (a.flightRecorder != null)
            a.record(1, "PulsarActor", "receive", "%s waiting for a message.Millis left: %s ", a, TimeUnit.MILLISECONDS.convert(timeout, unit));
        a.mailbox().await(iter, timeout, unit);
    }

    public static void timeout(Actor a) throws TimeoutException {
        a.record(1, "PulsarActor", "receive", "%s timed out", a);
        throw new TimeoutException();
    }
}
