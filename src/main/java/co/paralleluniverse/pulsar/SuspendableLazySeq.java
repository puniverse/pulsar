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
/*
 * Copyright (c) Rich Hickey. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 */


/* rich Jan 31, 2009 */
package co.paralleluniverse.pulsar;

import clojure.lang.ASeq;
import clojure.lang.IFn;
import clojure.lang.IHashEq;
import clojure.lang.IPending;
import clojure.lang.IPersistentCollection;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.Obj;
import clojure.lang.PersistentList;
import clojure.lang.RT;
import clojure.lang.Sequential;
import clojure.lang.Util;
import co.paralleluniverse.fibers.Suspendable;

public final class SuspendableLazySeq extends Obj implements ISeq, Sequential, IPending, IHashEq {
    private volatile IFn fn;
    private Object sv;
    private ISeq s;

    public SuspendableLazySeq(IFn fn) {
        this.fn = fn;
    }

    private SuspendableLazySeq(IPersistentMap meta, ISeq s) {
        super(meta);
        this.fn = null;
        this.s = s;
    }

    @Override
    public Obj withMeta(IPersistentMap meta) {
        return new SuspendableLazySeq(meta, seq());
    }

    private Object sval() {
        if (fn != null) {
            try {
                sv = fn.invoke();
                // fn = null; -- b/c of cloned continuations. the same lazy-seq object points to multiple clones via the fn ref
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw Util.sneakyThrow(e);
            }
        }
        if (sv != null)
            return sv;
        return s;
    }

    @Override
    final public ISeq seq() {
        sval();
        if (sv != null) {
            //Object ls = sv;
            //sv = null;
            while (sv instanceof SuspendableLazySeq) {
                sv = ((SuspendableLazySeq) sv).sval();
            }
            s = seq(sv);
            sv = null;
        }
        return s;
    }

    @Override
    public Object first() {
        seq();
        if (s == null)
            return null;
        return s.first();
    }

    @Override
    public ISeq next() {
        seq();
        if (s == null)
            return null;
        return s.next();
    }

    @Override
    public ISeq more() {
        seq();
        if (s == null)
            return PersistentList.EMPTY;
        return s.more();
    }

    @Override
    public int count() {
        return 1;
    }

    @Override
    public ISeq cons(Object o) {
        return RT.cons(o, seq());
    }

    @Override
    public IPersistentCollection empty() {
        return PersistentList.EMPTY;
    }

    @Override
    public boolean equiv(Object o) {
        return equals(o);
    }

    @Override
    public int hashCode() {
        ISeq s = seq();
        if (s == null)
            return 1;
        return Util.hash(s);
    }

    @Override
    public int hasheq() {
        ISeq s = seq();
        if (s == null)
            return 1;
        return Util.hasheq(s);
    }

    @Override
    public boolean equals(Object o) {
        ISeq s = seq();
        if (s != null)
            return s.equiv(o);
        else
            return (o instanceof Sequential || o instanceof java.util.List) && seq(o) == null;
    }

    @Override
    public boolean isRealized() {
        return fn == null;
    }

    static public ISeq seq(Object coll) {
        if (coll instanceof ASeq)
            return (ASeq) coll;
        else if (coll instanceof SuspendableLazySeq)
            return ((SuspendableLazySeq) coll).seq();
        else
            return RT.seq(coll);
    }
}
