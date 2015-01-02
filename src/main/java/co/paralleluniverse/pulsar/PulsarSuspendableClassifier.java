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

/**
 * @author pron
 */
import co.paralleluniverse.fibers.instrument.MethodDatabase;
import co.paralleluniverse.fibers.instrument.MethodDatabase.SuspendableType;
import co.paralleluniverse.fibers.instrument.SuspendableClassifier;

public class PulsarSuspendableClassifier implements SuspendableClassifier {
    @Override
    public SuspendableType isSuspendable(MethodDatabase db, String className, String superClassName, String[] interfaces, String methodName, String methodDesc, String methodSignature, String[] methodExceptions) {
        if (className.equals("clojure/lang/IFn")) {
            if (methodName.equals("invoke"))
                return SuspendableType.SUSPENDABLE_SUPER;
        }
        else if (className.startsWith("clojure/lang/IFn$") && methodName.equals("invokePrim"))
            return SuspendableType.SUSPENDABLE_SUPER;
        else if (isClojureUserFunction(db, className, superClassName, interfaces, methodName, methodDesc, methodSignature, methodExceptions))
            return SuspendableType.SUSPENDABLE; // circlespainter: making all Clojure functions suspendable [EVAL]

        return null;
    }

    private static boolean isClojureUserFunction(MethodDatabase db, String className, String superClassName, String[] interfaces, String methodName, String methodDesc, String methodSignature, String[] methodExceptions) {
        // Based mostly on http://nicholaskariniemi.github.io/2014/01/26/clojure-compilation.html and http://clojure.org/compilation
        // TODO try to reuse any decently packed Clojure compiler's logic (if there's any)
        return !isClojureCoreClassName(className)
                && isClojureFunctionName(className)
                && isClojureFunctionSuperClassName(superClassName)
                && isClojureFunctionInvocationMethodName(methodName);
    }

    private static boolean isClojureCoreClassName(String className) {
        return className != null && (className.startsWith("clojure/core$") || className.startsWith("clojure/lang/"));
    }

    private static boolean isClojureFunctionInvocationMethodName(String methodName) {
        return "invoke".equals(methodName) || "doInvoke".equals(methodName);
    }

    private static boolean isClojureFunctionSuperClassName(String superClassName) {
        return superClassName != null && (superClassName.endsWith("AFunction") || superClassName.endsWith("RestFn"));
    }

    private static boolean isClojureFunctionName(String className) {
        return className != null && className.contains("$");
    }
}