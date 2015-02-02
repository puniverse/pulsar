/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (c) 2013-2015, Parallel Universe Software Co. All rights reserved.
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
package co.paralleluniverse.pulsar.async;

import co.paralleluniverse.fibers.DefaultFiberScheduler;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.Suspendable;
import co.paralleluniverse.strands.Strand;
import co.paralleluniverse.strands.StrandFactory;
import co.paralleluniverse.strands.SuspendableRunnable;
import co.paralleluniverse.strands.SuspendableUtils;
import co.paralleluniverse.strands.Timeout;
import co.paralleluniverse.strands.channels.Channel;
import co.paralleluniverse.strands.channels.Channels;
import co.paralleluniverse.strands.channels.SendPort;
import co.paralleluniverse.strands.channels.Topic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Topic} that will spawn fibers from a factory and distribute messages to subscribers in parallel
 * using strands, optionally waiting for them to complete receive before delivering the next one. It is used
 * to implement core.async's `mult`'s.
 * 
 * @author circlespainter
 */
public class ParallelTopic<Message> extends Topic<Message> {
    private static final boolean stagedDefault = true;
    private static final StrandFactory strandFactoryDefault = DefaultFiberScheduler.getInstance();
    
    private final StrandFactory strandFactory;
    private final Channel<Message> internalChannel;
    private final Collection<SendPort<? super Message>> subscribersToBeLeftOpen;
    private final AtomicReference<Throwable> closingException = new AtomicReference<Throwable>();

    private ParallelTopic(final Channel<Message> internalChannel, final StrandFactory strandFactory, final boolean staged) {
        this.subscribersToBeLeftOpen = new CopyOnWriteArraySet<SendPort<? super Message>>();

        this.internalChannel = internalChannel;
        this.strandFactory = strandFactory;

        startDistributionLoop(staged);
    }
    
    /**
     * Creates a new ParallelTopic message distributor with the given buffer parameters, {@link StrandFactory} and staging behavior.
     *
     * @param bufferSize    The buffer size of this topic.
     * @param policy        The buffer policy of this topic.
     * @param strandFactory The {@llink StrandFactory} instance that will build the strands performing send operations to subscribers as well as the looping
     *                      receive strand.
     * @param staged        Whether all send operations to subscribers for a given message must be completed before initiating the subsequent one.
     */
    public ParallelTopic(final int bufferSize, final Channels.OverflowPolicy policy, final StrandFactory strandFactory, final boolean staged) {
        this(Channels.<Message>newChannel(bufferSize, policy), strandFactory, staged);
    }

    /**
     * Creates a new ParallelTopic staged message distributor with the given buffer parameters and {@link StrandFactory}.
     *
     * @param bufferSize    The buffer size of this topic.
     * @param policy        The buffer policy of this topic.
     * @param strandFactory The {@llink StrandFactory} instance that will build the strands performing send operations to subscribers as well as the looping
     *                      receive strand.
     */
    public ParallelTopic(final int bufferSize, final Channels.OverflowPolicy policy, final StrandFactory strandFactory) {
        this(bufferSize, policy, strandFactory, stagedDefault);
    }

    /**
     * Creates a new ParallelTopic message distributor using a fiber-creating {@link StrandFactory} and with the given buffer parameters and staging behavior.
     *
     * @param bufferSize    The buffer size of this topic.
     * @param policy        The buffer policy of this topic.
     * @param staged        Whether all send operations to subscribers for a given message must be completed before initiating the subsequent one.
     */
    public ParallelTopic(final int bufferSize, final Channels.OverflowPolicy policy, final boolean staged) {
        this(bufferSize, policy, strandFactoryDefault);
    }

    /**
     * Creates a new staged ParallelTopic message distributor using a fiber-creating {@link StrandFactory} and with the given buffer parameters.
     *
     * @param bufferSize    The buffer size of this topic.
     * @param policy        The buffer policy of this topic.
     */
    public ParallelTopic(final int bufferSize, final Channels.OverflowPolicy policy) {
        this(bufferSize, policy, stagedDefault);
    }

    /**
     * Creates a new ParallelTopic message distributor using a fiber-creating {@link StrandFactory} and with the given buffer parameters and staging behavior.
     *
     * @param bufferSize    The buffer size of this topic.
     * @param staged        Whether all send operations to subscribers for a given message must be completed before initiating the subsequent one.
     */
    public ParallelTopic(final int bufferSize, final boolean staged) {
        this(Channels.<Message>newChannel(bufferSize), strandFactoryDefault, staged);
    }

    /**
     * Creates a new staged ParallelTopic message distributor using a fiber-creating {@link StrandFactory} and with the given buffer parameters.
     *
     * @param bufferSize    The buffer size of this topic.
     */
    public ParallelTopic(final int bufferSize) {
        this(Channels.<Message>newChannel(bufferSize), strandFactoryDefault, stagedDefault);
    }

    /**
     * Subscribe a channel to receive messages sent to this topic.
     *
     * @param close Specifies if the channel should be closed when the topic is closed (by default it is).
     */
    public <T extends SendPort<? super Message>> T subscribe(final T sub, final boolean close) {
        if (close)
            return super.subscribe(sub);
        else {
            subscribersToBeLeftOpen.add(sub);
            getSubscribers().add(sub);
            return sub;
        }
    }

    @Override
    public void unsubscribe(SendPort<? super Message> sub) {
        super.unsubscribe(sub);
        subscribersToBeLeftOpen.remove(sub);
    }

    @Override
    public void unsubscribeAll() {
        super.unsubscribeAll();
        subscribersToBeLeftOpen.clear();
    }

    @Override
    public void send(final Message message) throws SuspendExecution, InterruptedException {
        internalChannel.send(message);
    }

    @Override
    public boolean send(final Message message, final Timeout timeout) throws SuspendExecution, InterruptedException {
        return internalChannel.send(message, timeout);
    }

    @Override
    public boolean send(final Message message, final long timeout, final TimeUnit unit) throws SuspendExecution, InterruptedException {
        return internalChannel.send(message, timeout, unit);
    }

    @Override
    public boolean trySend(final Message message) {
        return internalChannel.trySend(message);
    }

    @Override
    public void close() {
        sendClosed = true;
        internalChannel.close();
    }

    @Override
    public void close(Throwable t) {
        closingException.set(t);
        close();
    }

    @Suspendable
    private Strand startDistributionLoop(final boolean staged) {
        // TODO check if there are more efficient alternatives
        final ArrayList<Strand> stage = new ArrayList<Strand>();

        return strandFactory.newStrand(SuspendableUtils.runnableToCallable(new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                try {
                    for (;;) {
                        if (sendClosed) {
                            // Close subs
                            for (final SendPort<?> sub : getSubscribers()) {
                                if (!subscribersToBeLeftOpen.contains(sub)) {
                                    if (closingException.get() != null)
                                        sub.close(closingException.get());
                                    else
                                        sub.close();
                                }
                            }

                            return;
                        }

                        final Message m = internalChannel.receive();
                        if (m != null) {
                            if (staged)
                                stage.clear();

                            for (final SendPort<? super Message> sub : getSubscribers()) {
                                final Strand s = strandFactory.newStrand(SuspendableUtils.runnableToCallable(new SuspendableRunnable() {
                                    @Override
                                    public void run() throws SuspendExecution, InterruptedException {
                                        try {
                                            sub.send(m);
                                        } catch (Throwable t) {
                                            t.printStackTrace();
                                            throw new RuntimeException(t);
                                        }
                                    }
                                })).start();

                                if (staged)
                                    stage.add(s);
                            }

                            if (staged) {
                                for (final Strand s : stage)
                                    s.join();
                            }
                        } // Else sendClosed
                    }
                } catch (final ExecutionException ee) {
                    // This should never happen
                    throw new AssertionError(ee);
                }
            }
        })).start();
    }
}
