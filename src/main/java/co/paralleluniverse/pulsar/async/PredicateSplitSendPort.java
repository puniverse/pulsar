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

import co.paralleluniverse.strands.channels.SendPort;
import co.paralleluniverse.strands.channels.SplitSendPort;
import com.google.common.base.Predicate;

/**
 * A {@link SplitSendPort} based on a binary predicate to implement core.async's `split`.
 *
 * @author circlespainter
 */
public class PredicateSplitSendPort<M> extends SplitSendPort<M> {
    private final Predicate<M> p;
    private final SendPort<? super M> spTrue;
    private final SendPort<? super M> spFalse;

    public PredicateSplitSendPort(final Predicate<M> p, final SendPort<? super M> spTrue, final SendPort<? super M> spFalse) {
        this.p = p;
        this.spTrue = spTrue;
        this.spFalse = spFalse;
    }

    @Override
    public void close() {
        super.close();
        spTrue.close();
        spFalse.close();
    }

    @Override
    public void close(Throwable t) {
        super.close();
        spTrue.close(t);
        spFalse.close(t);
    }

    @Override
    protected SendPort<? super M> select(M m) {
        if (m == null)
            return null;

        if (p.apply(m))
            return spTrue;

        return spFalse;
    }
}
