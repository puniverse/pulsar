/*
 * Pulsar: lightweight threads and Erlang-like actors for Clojure.
 * Copyright (C) 2013-2015, Parallel Universe Software Co. All rights reserved.
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
package co.paralleluniverse.pulsar;

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.SuspendableAction1;
import co.paralleluniverse.strands.channels.DelegatingSendPort;
import co.paralleluniverse.strands.channels.SendPort;

/**
 * @author circlespainter
 */
public class CoreAsyncSendPort<T> extends DelegatingSendPort<T> {
    private final SuspendableAction1<T> sendAction;

    public CoreAsyncSendPort(final SendPort<T> target, final SuspendableAction1<T> sendAction) {
        super(target);
        this.sendAction = sendAction;
    }

    @Override
    public void send(T message) throws SuspendExecution, InterruptedException {
        sendAction.call(message);
    }
}
