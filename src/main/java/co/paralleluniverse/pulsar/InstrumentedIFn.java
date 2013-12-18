/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.pulsar;

import clojure.lang.Fn;
import clojure.lang.IFn;
import clojure.lang.ISeq;

/**
 *
 * @author pron
 */
public class InstrumentedIFn implements IFn, Fn, IInstrumented {
    public final IFn fn;

    public InstrumentedIFn(IFn fn) {
        this.fn = fn;
    }

    public Object invoke() {
        return fn.invoke();
    }

    public Object invoke(Object o) {
        return fn.invoke(o);
    }

    public Object invoke(Object o, Object o1) {
        return fn.invoke(o, o1);
    }

    public Object invoke(Object o, Object o1, Object o2) {
        return fn.invoke(o, o1, o2);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3) {
        return fn.invoke(o, o1, o2, o3);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4) {
        return fn.invoke(o, o1, o2, o3, o4);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5) {
        return fn.invoke(o, o1, o2, o3, o4, o5);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6, o7);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6, o7, o8);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6, o7, o8, o9);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6, o7, o8, o9, o10);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6, o7, o8, o9, o10, o11);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6, o7, o8, o9, o10, o11, o12);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6, o7, o8, o9, o10, o11, o12, o13);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13, Object o14) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6, o7, o8, o9, o10, o11, o12, o13, o14);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13, Object o14, Object o15) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6, o7, o8, o9, o10, o11, o12, o13, o14, o15);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13, Object o14, Object o15, Object o16) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6, o7, o8, o9, o10, o11, o12, o13, o14, o15, o16);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13, Object o14, Object o15, Object o16, Object o17) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6, o7, o8, o9, o10, o11, o12, o13, o14, o15, o16, o17);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13, Object o14, Object o15, Object o16, Object o17, Object o18) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6, o7, o8, o9, o10, o11, o12, o13, o14, o15, o16, o17, o18);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13, Object o14, Object o15, Object o16, Object o17, Object o18, Object o19) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6, o7, o8, o9, o10, o11, o12, o13, o14, o15, o16, o17, o18, o19);
    }

    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13, Object o14, Object o15, Object o16, Object o17, Object o18, Object o19, Object... os) {
        return fn.invoke(o, o1, o2, o3, o4, o5, o6, o7, o8, o9, o10, o11, o12, o13, o14, o15, o16, o17, o18, o19, os);
    }

    public Object applyTo(ISeq iseq) {
        return fn.applyTo(iseq);
    }

    public void run() {
        fn.run();
    }

    public Object call() throws Exception {
        return fn.call();
    }

    public int hashCode() {
        return fn.hashCode();
    }

    public boolean equals(Object obj) {
        if(obj instanceof InstrumentedIFn)
            obj = ((InstrumentedIFn)obj).fn;
        return fn.equals(obj);
    }

    public String toString() {
        return fn.toString();
    }
}
