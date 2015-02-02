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

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.SuspendableAction2;
import co.paralleluniverse.strands.SuspendableCallable;
import co.paralleluniverse.strands.channels.Channel;
import co.paralleluniverse.strands.channels.ReceivePort;
import co.paralleluniverse.strands.channels.SendPort;
import co.paralleluniverse.strands.channels.transfer.Pipeline;

/**
 * A {@link Pipeline} leaving inputs untouched. It is used (with `parallelism`set to 0) to implement core.async's `pipe`.
 *
 * @author circlespainter
 */
public class IdentityPipeline<T> extends Pipeline<T, T> {
    public IdentityPipeline(ReceivePort<? extends T> from, SendPort<? super T> to, int parallelism, Boolean closeTo) {
        super (
            from, to,
            new SuspendableAction2<T, Channel<T>>() {
                @Override
                public void call(T t, Channel<T> tCh) throws SuspendExecution, InterruptedException {
                    tCh.send(t);
                    tCh.close();
                }
            },
            parallelism, closeTo != null ? closeTo : false
        );
    }
}
