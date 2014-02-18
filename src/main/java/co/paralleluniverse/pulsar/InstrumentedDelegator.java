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

public class InstrumentedDelegator<T> implements IInstrumented {
    protected final T d;

    public InstrumentedDelegator(T d) {
        this.d = d;
    }
    
    
    @Override
    public int hashCode() {
        return d.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return d.equals(obj);
    }

    @Override
    public String toString() {
        return d.toString();
    }
}
