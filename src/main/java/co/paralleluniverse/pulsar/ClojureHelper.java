/*
 * Pulsar: lightweight threads and Erlang-like actors for Clojure.
 * Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
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
import clojure.lang.Var;
import co.paralleluniverse.fibers.Instrumented;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.instrument.MethodDatabase.ClassEntry;
import co.paralleluniverse.fibers.instrument.Retransform;
import co.paralleluniverse.strands.SuspendableCallable;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Method;
import java.util.Map;
import org.objectweb.asm.Type;

/**
 *
 * @author pron
 */
public class ClojureHelper {
    static {
        // These methods need not be instrumented. we mark them so that verifyInstrumentation doesn't fail when they're on the call-stack
        Retransform.addWaiver("clojure.lang.AFn", "applyToHelper");
        Retransform.addWaiver("clojure.lang.AFn", "applyTo");
        Retransform.addWaiver("clojure.core$apply", "invoke");

        // mark all IFn methods as suspendable
        Retransform.getMethodDB().getClassEntry(Type.getInternalName(IFn.class)).setAll(true);
    }

    public static Object retransform(Object thing) throws UnmodifiableClassException {
        final Class clazz;
        if (thing instanceof IFn)
            clazz = thing.getClass();
        else if (thing instanceof Class)
            clazz = (Class) thing;
        else
            throw new IllegalArgumentException("Not a class or an IFn: " + thing + " (type: " + thing.getClass() + ")");
        if (!(IFn.class.isAssignableFrom(clazz)))
            throw new IllegalArgumentException("Class " + clazz + " does not implement IFn");

        if (clazz.isAnnotationPresent(Instrumented.class))
            return thing;

        try {
            // Clojure might break up a single function into several classes. We must instrument them all.
            for (Map.Entry<String, ClassEntry> entry : Retransform.getMethodDB().getInnerClassesEntries(Type.getInternalName(clazz)).entrySet()) {
                final String className = entry.getKey();
                final ClassEntry ce = entry.getValue();
                final Class cls = Class.forName(className.replaceAll("/", "."), false, clazz.getClassLoader());
                //System.out.println("---- " + cls + " " + IFn.class.isAssignableFrom(cls));
                ce.setRequiresInstrumentation(true);
                Method[] methods = cls.getMethods();
                for (Method method : methods) {
                    if (method.getName().equals("invoke") || method.getName().equals("doInvoke"))
                        ce.set(method.getName(), Type.getMethodDescriptor(method), true);
                }
                Retransform.retransform(cls);
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        return thing;
    }

    ////////
    public static SuspendableCallable<Object> asSuspendableCallable(final IFn fn) {
        if (!ClojureHelper.isInstrumented(fn.getClass()))
            throw new IllegalArgumentException("Function " + fn + " has not been instrumented");

        final Object binding = Var.cloneThreadBindingFrame(); // Clojure treats bindings as an InheritableThreadLocal, yet sets them in a ThreadLocal...
        return new SuspendableCallable<Object>() {
            @Override
            public Object run() throws SuspendExecution, InterruptedException {
                Var.resetThreadBindingFrame(binding);
                return suspendableInvoke(fn);
//                final Object origBinding = Var.getThreadBindingFrame();
//                try {
//                    Var.resetThreadBindingFrame(binding);
//                    return suspendableInvoke(fn);
//                } finally {
//                    Var.resetThreadBindingFrame(origBinding);
//                }
            }
        };
    }

    private static Object suspendableInvoke(IFn fn) throws SuspendExecution {
        return fn.invoke();
    }

    public static boolean isInstrumented(Class clazz) {
        return clazz.isAnnotationPresent(Instrumented.class);
    }

    static public RuntimeException sneakyThrow(Throwable t) {
        // http://www.mail-archive.com/javaposse@googlegroups.com/msg05984.html
        if (t == null)
            throw new NullPointerException();
        ClojureHelper.<RuntimeException>sneakyThrow0(t);
        return null;
    }

    @SuppressWarnings("unchecked")
    static private <T extends Throwable> T sneakyThrow0(Throwable t) throws T {
        throw (T) t;
    }
}
