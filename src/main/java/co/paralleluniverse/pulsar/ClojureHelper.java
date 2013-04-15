/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.pulsar;

import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.Var;
import co.paralleluniverse.actors.MessageProcessor;
import co.paralleluniverse.fibers.Instrumented;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.instrument.MethodDatabase;
import co.paralleluniverse.fibers.instrument.Retransform;
import co.paralleluniverse.strands.SuspendableCallable;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import org.objectweb.asm.Type;

/**
 *
 * @author pron
 */
public class ClojureHelper {
    public static void retransform(IFn fn) throws UnmodifiableClassException {
        final Class clazz = fn.getClass();
        MethodDatabase.ClassEntry entry = Retransform.getMethodDB().getClassEntry(Type.getInternalName(clazz));
        entry.setRequiresInstrumentation(true);
        Method[] methods = clazz.getMethods();
        for (Method method : methods) {
            if (method.getName().equals("invoke") || method.getName().equals("doInvoke")) {
                // System.out.println("X: request retransform " + Type.getInternalName(clazz) + "." + method.getName() + Type.getMethodDescriptor(method));
                entry.set(method.getName(), Type.getMethodDescriptor(method), true);
            }
        }
        //System.out.println("XXXX: " + fn.getClass());
        Retransform.retransform(fn.getClass());
    }

    public static SuspendableCallable<Object> asSuspendableCallable(final IFn fn) {
        if (!isInstrumented(fn.getClass()))
            throw new IllegalArgumentException("Function " + fn + " has not been instrumented");

        final Object binding = Var.cloneThreadBindingFrame(); // Clojure treats bindings as an InheritableThreadLocal, yet sets them in a ThreadLocal...
        return new SuspendableCallable<Object>() {
            @Override
            public Object run() throws SuspendExecution, InterruptedException {
                final Object origBinding = Var.getThreadBindingFrame();
                try {
                    Var.resetThreadBindingFrame(binding);
                    return fn.invoke();
                } catch (Exception e) {
                    throw sneakyThrow(e);
                } finally {
                    Var.resetThreadBindingFrame(origBinding);
                }
            }
        };
    }

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
            case "sconds":
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

    public static MessageProcessor<Object> asMessageProcessor(final IFn fn) {
        return new MessageProcessor<Object>() {
            @Override
            public boolean process(Object msg) throws SuspendExecution, InterruptedException {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        };
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
