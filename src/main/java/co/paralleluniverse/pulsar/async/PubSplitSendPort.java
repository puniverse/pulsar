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

import co.paralleluniverse.common.util.Pair;
import co.paralleluniverse.concurrent.util.EnhancedAtomicReference;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.channels.SendPort;
import co.paralleluniverse.strands.channels.SplitSendPort;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A {@link SplitSendPort}-based helper for core.async's `Pub`.
 *
 * @author circlespainter
 */
public class PubSplitSendPort<T> extends SplitSendPort<T> {
    private final EnhancedAtomicReference<Map<Object, Pair<Object, SendPort<T>>>> topics = new EnhancedAtomicReference<Map<Object, Pair<Object, SendPort<T>>>>();

    private final Function<T, Object> selector;
    private final Function<Object, Pair<Object, SendPort<T>>> spFn;

    public PubSplitSendPort(final Function<T, Object> selector, final Function<Object, Pair<Object, SendPort<T>>> spFn) {
        this.topics.set(ImmutableMap.<Object, Pair<Object, SendPort<T>>>of());
        this.selector = selector;
        this.spFn = spFn;
    }

    public Object ensure(final Object tKey) {
        final Map<?, Pair<Object, SendPort<T>>> currentTopics = topics.get();

        if (currentTopics.containsKey(tKey))
            return currentTopics.get(tKey).getFirst();
        else {
            if (spFn != null) {
                topics.swap(new Function<Map<Object, Pair<Object, SendPort<T>>>, Map<Object, Pair<Object, SendPort<T>>>>() {
                    @Override
                    public Map<Object, Pair<Object, SendPort<T>>> apply(final Map<Object, Pair<Object, SendPort<T>>> spMap) {
                        final HashMap<Object, Pair<Object, SendPort<T>>> newSpMap = new HashMap<Object, Pair<Object, SendPort<T>>>(spMap);
                        newSpMap.put(tKey, spFn.apply(tKey));
                        return ImmutableMap.copyOf(newSpMap);
                    }
                });
                return topics.get().get(tKey).getFirst();
            } else
                return null;
        }
    }

    public Object get(final Object tKey) {
        return topics.get().get(tKey).getFirst();
    }

    public void reset() {
        topics.set(ImmutableMap.<Object, Pair<Object, SendPort<T>>>of());
    }

    public void remove(final Object tKey) {
        topics.swap(new Function<Map<Object, Pair<Object, SendPort<T>>>, Map<Object, Pair<Object, SendPort<T>>>>() {
            @Override
            public Map<Object, Pair<Object, SendPort<T>>> apply(final Map<Object, Pair<Object, SendPort<T>>> spMap) {
                if (spMap.containsKey(tKey)) {
                    final HashMap<Object, Pair<Object, SendPort<T>>> newSpMap = new HashMap<Object, Pair<Object, SendPort<T>>>(spMap);
                    newSpMap.remove(tKey);
                    return ImmutableMap.copyOf(newSpMap);
                } else
                    return spMap;
            }
        });
    }

    @Override
    protected SendPort<? super T> select(final T t) {
        final Object tKey = selector.apply(t);
        ensure(tKey);
        return topics.get().get(tKey).getSecond();
    }

    @Override
    public void close(Throwable t) {
        final Collection<Pair<Object, SendPort<T>>> currentSPs = topics.get().values();
        for(final Pair<Object, SendPort<T>> sp : currentSPs) {
            sp.getSecond().close(t);
        }
    }

    @Override
    public void close() {
        super.close();
        final Collection<Pair<Object, SendPort<T>>> currentSPs = topics.get().values();
        for(final Pair<Object, SendPort<T>> sp : currentSPs) {
            sp.getSecond().close();
        }
    }

    @Override
    public boolean send(T t, long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        return super.send(t, timeout, unit);
    }
}
