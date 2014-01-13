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
import co.paralleluniverse.actors.ActorRegistry;
import co.paralleluniverse.fibers.Instrumented;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.instrument.JavaAgent;
import co.paralleluniverse.fibers.instrument.MethodDatabase;
import co.paralleluniverse.fibers.instrument.MethodDatabase.ClassEntry;
import co.paralleluniverse.fibers.instrument.Retransform;
import co.paralleluniverse.strands.SuspendableCallable;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.common.base.Function;
import org.objectweb.asm.Type;

/**
 *
 * @author pron
 */
public class ClojureHelper {
    // This whole mess with lastInstrumented is a heuristic to save us from calling clazz.isAnnotationPresent(Instrumented.class)),
    // which turns out to be *slow*.
    private static final int NUM_LAST_INSTRUMENTED = 3;
    private static final ThreadLocal<Class[]> lastInstrumented = new ThreadLocal<Class[]>() {
        @Override
        protected Class[] initialValue() {
            return new Class[NUM_LAST_INSTRUMENTED];
        }
    };
    private static final ThreadLocal<Int> lastInstrumentedIndex = new ThreadLocal<Int>() {
        @Override
        protected Int initialValue() {
            return new Int();
        }
    };

    static {
        if(!JavaAgent.isActive())
            throw new RuntimeException("Java agent not running");

        // These methods need not be instrumented. we mark them so that verifyInstrumentation doesn't fail when they're on the call-stack
        Retransform.addWaiver("clojure.lang.AFn", "applyToHelper");
        Retransform.addWaiver("clojure.lang.AFn", "applyTo");
        Retransform.addWaiver("clojure.lang.RestFn", "invoke");
        Retransform.addWaiver("clojure.lang.RestFn", "doInvoke");
        Retransform.addWaiver("clojure.core$apply", "invoke");
        Retransform.addWaiver("co.paralleluniverse.pulsar.InstrumentedIFn", "invoke");

        Retransform.addWaiver("co.paralleluniverse.actors.behaviors.EventHandler", "handleEvent");

        // mark all IFn methods as suspendable
        Retransform.getMethodDB().getClassEntry(Type.getInternalName(IFn.class)).setAll(MethodDatabase.SuspendableType.SUSPENDABLE_SUPER);

        // register kryo serializers for clojure types
        if (ActorRegistry.hasGlobalRegistry()) {
            try {
                Class.forName("co.paralleluniverse.pulsar.galaxy.ClojureKryoSerializers");
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
        }
    }

    public static Object retransform(Object thing, Collection<Class> protocols) throws UnmodifiableClassException {
        if (thing instanceof IInstrumented)
            return thing;

        if (isInLastInstrumented(thing.getClass()))
            return new InstrumentedIFn((IFn) thing);
        return retransform1(thing, protocols);
    }

    private static Object retransform1(Object thing, Collection<Class> protocols) throws UnmodifiableClassException {
        final boolean isClass = thing instanceof Class;
        final Class clazz = isClass ? (Class) thing : thing.getClass();

        final boolean isIFn = IFn.class.isAssignableFrom(clazz);

        if (IInstrumented.class.isAssignableFrom(clazz) || clazz.isAnnotationPresent(Instrumented.class)) {
            if (isIFn) {
                addToLastInstrumented(clazz);
                return !isClass ? new InstrumentedIFn((IFn) thing) : thing;
            } else
                return thing;
        }

        if (!isIFn && clazz.isInterface()) {
            Retransform.getMethodDB().getClassEntry(Type.getInternalName(clazz)).setAll(MethodDatabase.SuspendableType.SUSPENDABLE_SUPER);
            return thing;
        }

        if (!isIFn && protocols == null)
            throw new IllegalArgumentException("Cannot retransform " + thing + ". Not an IFn and a protocol not given");

        final Set<String> protocolMethods;
        if (!isIFn) {
            protocolMethods = new HashSet<String>();
            for (Class protocol : protocols)
                for (Method m : protocol.getMethods())
                    protocolMethods.add(m.getName());
        } else
            protocolMethods = null;

//        if (!isIFn) {
//            Retransform.addClassLoadListener(new DumpClassListener(clazz));
//        }

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
                    if ((IFn.class.isAssignableFrom(cls) && (method.getName().equals("invoke") || method.getName().equals("doInvoke")))
                            || (cls == clazz && !isIFn && protocolMethods.contains(method.getName()))) { // method.getDeclaringClass().equals(clazz))) {
                        ce.set(method.getName(), Type.getMethodDescriptor(method), MethodDatabase.SuspendableType.SUSPENDABLE);
                    }
                }
                Retransform.retransform(cls);
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        if (isIFn) {
            addToLastInstrumented(clazz);
            return !isClass ? new InstrumentedIFn((IFn) thing) : thing;
        } else
            return thing;
    }

    ////////
    public static SuspendableCallable<Object> asSuspendableCallable(final IFn fn) {
        if (!(fn instanceof InstrumentedIFn))
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

    static Object suspendableInvoke(IFn fn) throws SuspendExecution {
        return fn.invoke();
    }

    public static boolean isInstrumented(Class clazz) {
        return clazz.isAnnotationPresent(Instrumented.class);
    }

    private static boolean isInLastInstrumented(Class cls) {
        Class[] cs = lastInstrumented.get();
        for (Class c : cs) {
            if (c == cls)
                return true;
        }
        return false;
    }

    private static void addToLastInstrumented(Class cls) {
        Int ind = lastInstrumentedIndex.get();
        lastInstrumented.get()[ind.i] = cls;
        ind.i = (ind.i + 1) % NUM_LAST_INSTRUMENTED;
    }

    private static class Int {
        public int i;
    }

    private static Collection<Class<?>> supers(Class<?> c, Collection<Class<?>> s) {
        if (c == null)
            return s;

        s.add(c);
        for (Class iface : c.getInterfaces())
            supers(iface, s);
        supers(c.getSuperclass(), s);

        return s;
    }

    private static class DumpClassListener implements Retransform.ClassLoadListener {
        private final Class clazz;

        public DumpClassListener(Class clazz) {
            this.clazz = clazz;
        }

        @Override
        public void beforeTransform(String className, Class clazz, byte[] data) {
            if (clazz.equals(this.clazz)) {
                System.out.println("=== BEFORE ================================================");
                Retransform.dumpClass(className, data);
            }
        }

        @Override
        public void afterTransform(String className, Class clazz, byte[] data) {
            if (clazz.equals(this.clazz)) {
                System.out.println("=== AFTER ================================================");
                Retransform.dumpClass(className, data);
            }
        }

        @Override
        public int hashCode() {
            int hash = 3;
            hash = 31 * hash + Objects.hashCode(this.clazz);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            final DumpClassListener other = (DumpClassListener) obj;
            if (!Objects.equals(this.clazz, other.clazz))
                return false;
            return true;
        }
    }
}
