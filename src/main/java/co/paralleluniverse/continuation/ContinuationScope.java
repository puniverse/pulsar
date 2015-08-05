/*
 * Pulsar
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
package co.paralleluniverse.continuation;

import clojure.lang.Keyword;
import co.paralleluniverse.fibers.Suspend;

/**
 * @author pron
 */
public class ContinuationScope extends Suspend {
    private final Keyword scope;

    public ContinuationScope(Keyword scope) {
        if (scope == null)
            throw new NullPointerException("scope is null");
        this.scope = scope;
    }

    public Keyword getScope() {
        return scope;
    }

    public boolean isScope(Keyword pauseScope) {
        return scope.equals(pauseScope);
    }

    @Override
    public String toString() {
        return "ContinuationScope{" + scope + '}';
    }
}
