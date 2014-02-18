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

package co.paralleluniverse.pulsar;

import clojure.lang.IFn;
import co.paralleluniverse.fibers.FiberAsync;

public final class ClojureFiberAsync extends FiberAsync<Object, Void, Exception> {
    private final IFn fn;

    public ClojureFiberAsync(IFn fn) {
        this.fn = fn;
    }

    @Override
    protected final Void requestAsync() {
        fn.invoke(this);
        return null;
    }

    public final void complete(Object res) {
        asyncCompleted(res);
    }

    public final void fail(Throwable t) {
        asyncFailed(t);
    }
}
