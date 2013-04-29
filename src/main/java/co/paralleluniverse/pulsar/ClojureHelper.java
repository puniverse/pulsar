/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.pulsar;

import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.Var;
import co.paralleluniverse.fibers.Instrumented;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.instrument.MethodDatabase.ClassEntry;
import co.paralleluniverse.fibers.instrument.Retransform;
import co.paralleluniverse.strands.SuspendableCallable;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.objectweb.asm.Type;

/**
 *
 * @author pron
 */
public class ClojureHelper {
    public static IFn retransform(IFn fn) throws UnmodifiableClassException {
        final Class clazz = fn.getClass();
        if (clazz.isAnnotationPresent(Instrumented.class))
            return fn;

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
        return fn;
    }

    public static SuspendableCallable<Object> asSuspendableCallable(final IFn fn) {
        if (!isInstrumented(fn.getClass()))
            throw new IllegalArgumentException("Function " + fn + " has not been instrumented");

        final Object binding = Var.cloneThreadBindingFrame(); // Clojure treats bindings as an InheritableThreadLocal, yet sets them in a ThreadLocal...
        return new SuspendableCallable<Object>() {
            @Override
            public Object run() throws SuspendExecution, InterruptedException {
                Var.resetThreadBindingFrame(binding);
                try {
                return suspendableInvoke(fn);
                } catch(Throwable e) {
                    e.printStackTrace();
                    return null;
                } finally {
                    System.out.println("DONE!");
                }
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

    ////////
    public static TimeUnit keywordToUnit(Keyword unit) {
        switch (unit.getName()) {
            case "nanoseconds":
            case "nanos":
                return TimeUnit.NANOSECONDS;
            case "microseconds":
            case "us":
                return TimeUnit.MICROSECONDS;
            case "milliseconds":
            case "millis":
            case "ms":
                return TimeUnit.MILLISECONDS;
            case "seconds":
            case "sec":
                return TimeUnit.SECONDS;
            case "minutes":
            case "mins":
                return TimeUnit.MINUTES;
            case "hours":
            case "hrs":
                return TimeUnit.HOURS;
            case "days":
                return TimeUnit.DAYS;
            default:
                throw new IllegalArgumentException("Unrecognized time unit " + unit);
        }
    }

    private static boolean isInstrumented(Class clazz) {
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
