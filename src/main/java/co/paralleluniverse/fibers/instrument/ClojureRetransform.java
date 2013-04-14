/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.fibers.instrument;

import clojure.lang.IFn;
import co.paralleluniverse.common.util.Exceptions;
import co.paralleluniverse.fibers.Instrumented;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.SuspendableCallable;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.objectweb.asm.Type;

/**
 *
 * @author pron
 */
public class ClojureRetransform {
    public static void retransform(IFn fn) throws UnmodifiableClassException {
        final Class clazz = fn.getClass();
        MethodDatabase.ClassEntry entry = Retransform.db.getClassEntry(Type.getInternalName(clazz));
        entry.setRequiresInstrumentation(true);
        Method[] methods = clazz.getMethods();
        for (Method method : methods) {
            if (method.getName().equals("invoke") || method.getName().equals("doInvoke")) {
                // System.out.println("X: request retransform " + Type.getInternalName(clazz) + "." + method.getName() + Type.getMethodDescriptor(method));
                entry.set(method.getName(), Type.getMethodDescriptor(method), true);
            }
        }
        Retransform.retransform(fn.getClass());
    }

    public static SuspendableCallable<Object> wrap(final IFn fn) {
        if (!isInstrumented(fn.getClass()))
            throw new IllegalArgumentException("Function " + fn + " has not been instrumented");
        return new SuspendableCallable<Object>() {
            @Override
            public Object run() throws SuspendExecution, InterruptedException {
                try {
                    return fn.call();
                } catch (Exception e) {
                    throw Exceptions.rethrow(e);
                }
            }
        };
    }

    private static boolean isInstrumented(Class clazz) {
        return clazz.isAnnotationPresent(Instrumented.class);
    }
}
