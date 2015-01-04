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
 * @author circlespainter
 */
import co.paralleluniverse.fibers.instrument.MethodDatabase;
import co.paralleluniverse.fibers.instrument.MethodDatabase.SuspendableType;
import co.paralleluniverse.fibers.instrument.SuspendableClassifier;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterators;

import java.util.Arrays;
import java.util.List;

public class PulsarSuspendableClassifier implements SuspendableClassifier {
    public static final List<String> CLOJURE_FUNCTION_BASE_INTERFACES = Arrays.asList(new String[] { "clojure/lang/IFn", "clojure/lang/IFn$" } );
    public static final List<String> CLOJURE_FUNCTION_BASE_INVOCATION_METHODS = Arrays.asList(new String[] { "invoke", "invokePrim" });

    //////////////////////////////////
    // Clojure auto-instrument support
    //////////////////////////////////

    public static final String CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_NAME = "co.paralleluniverse.pulsar.instrument.auto";
    public static final String CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_VALUE_ALL = "all";
    public static final String CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_VALUE_ANON = "anon";

    public static final String CLOJURE_FUNCTION_ANONYMOUS_CLASS_NAME_MARKER = "$fn__";
    public static final String CLOJURE_FUNCTION_CLASS_NAME_MARKER = "$";
    public static final List<String> CLOJURE_CORE_PACKAGE_NAMES = Arrays.asList(new String[] { "clojure/core$", "clojure/lang/" } );
    public static final List<String> CLOJURE_FUNCTION_BASE_CLASSES = Arrays.asList(new String[] { "clojure/lang/AFunction", "clojure/lang/RestFn" } );
    public static final List<String> CLOJURE_FUNCTION_ADDITIONAL_INVOCATION_METHODS = Arrays.asList(new String[] { "doInvoke", "applyTo", "applyToHelper", "call", "run" } );

    @Override
    public SuspendableType isSuspendable(final MethodDatabase db, final String className, final String superClassName, final String[] interfaces, final String methodName, final String methodDesc, final String methodSignature, final String[] methodExceptions) {
        if (CLOJURE_FUNCTION_BASE_INTERFACES.contains(className) && CLOJURE_FUNCTION_BASE_INVOCATION_METHODS.contains(methodName))
            return SuspendableType.SUSPENDABLE_SUPER;

        //////////////////////////////////
        // Clojure auto-instrument support
        //////////////////////////////////

        // Refs (based mostly on first two):
        //
        // - http://nicholaskariniemi.github.io/2014/01/26/clojure-compilation.html
        // - http://clojure.org/compilation
        // - https://github.com/clojure/clojure/blob/clojure-1.6.0/src/jvm/clojure/lang/Compiler.java#L3462
        // - https://github.com/clojure/clojure/blob/clojure-1.6.0/src/jvm/clojure/lang/Compiler.java#L2800
        // - https://github.com/pallet/ritz/blob/develop/repl-utils/src/ritz/repl_utils/mangle.clj

        // TODO Finish / improve protocols / datatypes
        // TODO Try to reuse any decently packed Clojure compiler's logic (if there's any)

        if (autoInstrumentAnonymousFunctionsOnly()) {
            if (isClojureAnonymousFunctionClassName(className))
                return SuspendableType.SUSPENDABLE; // circlespainter: making all Clojure functions suspendable [EVAL]
        }  else if (autoInstrumentEverythingClojure()) {
            if (isClojureUserFunction(className, superClassName, methodName))
                return SuspendableType.SUSPENDABLE; // circlespainter: making all Clojure functions suspendable [EVAL]
            else if (isPossiblyProtocolMethodDeclaration(className, superClassName, interfaces, methodName, methodDesc, methodSignature, methodExceptions))
                return SuspendableType.SUSPENDABLE_SUPER;
            else if (isPossiblyProtocolMethodImplementation(className, superClassName, interfaces, methodName, methodDesc, methodSignature, methodExceptions))
                return SuspendableType.SUSPENDABLE;
        }

        return null;
    }

    private boolean isPossiblyProtocolMethodDeclaration(final String className, final String superClassName, final String[] interfaces, final String methodName, final String methodDesc, final String methodSignature, final String[] methodExceptions) {
        // TODO Implement; heuristics: all interface methods only referencing Object` in their signature
        return false;
    }

    private boolean isPossiblyProtocolMethodImplementation(final String className, final String superClassName, final String[] interfaces, final String methodName, final String methodDesc, final String methodSignature, final String[] methodExceptions) {
        // TODO Implement; heuristics: all methods in classes implementing `IRecord`, `IType` or `IObj` implementing protocol method decls
        return false;
    }

    private static boolean autoInstrumentAnonymousFunctionsOnly() {
        return CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_VALUE_ANON.equals(System.getProperty(CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_NAME));
    }

    private static boolean autoInstrumentEverythingClojure() {
        return CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_VALUE_ALL.equals(System.getProperty(CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_NAME));
    }

    private static boolean isClojureUserFunction(final String className, final String superClassName, final String methodName) {
        return ! isClojureCoreClassName(className)
                && isClojureFunctionClassName(className)
                && CLOJURE_FUNCTION_BASE_CLASSES.contains(superClassName)
                && (CLOJURE_FUNCTION_BASE_INVOCATION_METHODS.contains(methodName)
                    || CLOJURE_FUNCTION_ADDITIONAL_INVOCATION_METHODS.contains(methodName));
    }

    private static boolean isClojureCoreClassName(final String className) {
        return className != null && Iterators.filter(CLOJURE_CORE_PACKAGE_NAMES.iterator(), new Predicate<String>() {
            @Override
            public boolean apply(String packageName) {
                return className.startsWith(packageName);
            }
        }).hasNext();
    }

    private static boolean isClojureFunctionClassName(final String className) {
        return className != null && className.contains(CLOJURE_FUNCTION_CLASS_NAME_MARKER);
    }

    private static boolean isClojureAnonymousFunctionClassName(final String className) {
        return className != null && className.contains(CLOJURE_FUNCTION_ANONYMOUS_CLASS_NAME_MARKER);
    }
}