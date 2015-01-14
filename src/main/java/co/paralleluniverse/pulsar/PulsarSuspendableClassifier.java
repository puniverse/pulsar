/*
 * Pulsar: lightweight threads and Erlang-like actors for Clojure.
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
package co.paralleluniverse.pulsar;

/**
 * @author pron
 * @author circlespainter
 */
import co.paralleluniverse.fibers.instrument.LogLevel;
import co.paralleluniverse.fibers.instrument.MethodDatabase;
import co.paralleluniverse.fibers.instrument.MethodDatabase.SuspendableType;
import co.paralleluniverse.fibers.instrument.SimpleSuspendableClassifier;
import co.paralleluniverse.fibers.instrument.SuspendableClassifier;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class PulsarSuspendableClassifier implements SuspendableClassifier {
    public static final List<String> CLOJURE_FUNCTION_BASE_INTERFACES = Arrays.asList("clojure/lang/IFn", "clojure/lang/IFn$");
    public static final List<String> CLOJURE_FUNCTION_BASE_INVOCATION_METHODS = Arrays.asList("invoke", "invokePrim");

    /////////////////////////////////////////////////////
    // Clojure auto-instrument support (EVAL/SPERIMENTAL)
    /////////////////////////////////////////////////////

    public static final String CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_NAME = "co.paralleluniverse.pulsar.instrument.auto";
    public static final String CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_VALUE_ALL = "all";
    public static final String CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_VALUE_ANON = "anon";

    public static final String CLOJURE_FUNCTION_ANONYMOUS_CLASS_NAME_MARKER = "$fn__";
    public static final String CLOJURE_FUNCTION_CLASS_NAME_MARKER = "$";
    public static final List<String> CLOJURE_FUNCTION_BASE_CLASSES = Arrays.asList("clojure/lang/AFn", "clojure/lang/AFunction", "clojure/lang/RestFn", "clojure/lang/MultiFn");
    public static final List<String> CLOJURE_FUNCTION_ADDITIONAL_INVOCATION_METHODS = Arrays.asList("doInvoke", "applyTo", "applyToHelper", "call", "run");

    private static final String CLOJURE_SOURCE_EXTENSION = ".clj";
    private static final List<String> CLOJURE_DATATYPE_INTERFACES = Arrays.asList("clojure/lang/IObj", "clojura.lang.IType", "clojura.lang.IRecord");
    private static final String[] AUTO_SUSPENDABLES = new String[] { "clojure-auto-suspendables" };
    private static final String[] AUTO_SUSPENDABLE_SUPERS = new String[] { "clojure-auto-suspendable-supers" };

    private final SimpleSuspendableClassifier autoSuspendables;
    private final boolean autoInstrumentAnonymousFunctionsOnly;
    private final boolean autoInstrumentEverythingClojure;

    public PulsarSuspendableClassifier() {
        this.autoSuspendables = new SimpleSuspendableClassifier(this.getClass().getClassLoader(), AUTO_SUSPENDABLES, AUTO_SUSPENDABLE_SUPERS);
        this.autoInstrumentAnonymousFunctionsOnly = CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_VALUE_ANON.equals(System.getProperty(CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_NAME));
        this.autoInstrumentEverythingClojure =  CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_VALUE_ALL.equals(System.getProperty(CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_NAME));
    }

    @Override
    public SuspendableType isSuspendable(final MethodDatabase db, final String sourceName, final String sourceDebugInfo,
                                         final boolean isInterface, final String className, final String superClassName,
                                         final String[] interfaces, final String methodName, final String methodDesc,
                                         final String methodSignature, final String[] methodExceptions) {
        if (CLOJURE_FUNCTION_BASE_INTERFACES.contains(className) && CLOJURE_FUNCTION_BASE_INVOCATION_METHODS.contains(methodName))
            return SuspendableType.SUSPENDABLE_SUPER;

        /////////////////////////////////////////////////////
        // Clojure auto-instrument support (EVAL/SPERIMENTAL)
        /////////////////////////////////////////////////////

        // Refs (based mostly on first two):
        //
        // - http://nicholaskariniemi.github.io/2014/01/26/clojure-compilation.html
        // - http://clojure.org/compilation
        // - https://github.com/clojure/clojure/blob/clojure-1.6.0/src/jvm/clojure/lang/Compiler.java#L3462
        // - https://github.com/clojure/clojure/blob/clojure-1.6.0/src/jvm/clojure/lang/Compiler.java#L2800
        // - https://github.com/pallet/ritz/blob/develop/repl-utils/src/ritz/repl_utils/mangle.clj

        // TODO Finish / improve protocols / datatypes
        // TODO Try to reuse any decently packed Clojure compiler's logic (if there's any)

        if (autoInstrument()) {
            final SuspendableType declared = autoSuspendables.isSuspendable(db, sourceName, sourceDebugInfo, isInterface, className, superClassName, interfaces, methodName, methodDesc, methodSignature, methodExceptions);
            if (declared != null && declared != SuspendableType.NON_SUSPENDABLE) {
                db.log(LogLevel.DEBUG, "[PulsarSuspendableClassifier] Auto-* mode, found suspendable Clojure RT '%s:%s#%s(%s)'", nullToEmpty(sourceName), className, methodName, nullToEmpty(methodSignature));
                return declared;
            }

            if (isPossiblyClojureSourceName(sourceName)) {
                if (autoInstrumentAnonymousFunctionsOnly()) {
                    if (isClojureAnonymousFunctionClassName(className)) {
                        db.log(LogLevel.DEBUG, "[PulsarSuspendableClassifier] Auto-anon mode, found suspendable Clojure anon '%s:%s#%s(%s)'", nullToEmpty(sourceName), className, methodName, nullToEmpty(methodSignature));
                        return SuspendableType.SUSPENDABLE;
                    }
                } else if (autoInstrumentEverythingClojure()) {
                    if (isPossiblyProtocolMethodDeclaration(sourceName, isInterface)) {
                        db.log(LogLevel.DEBUG, "[PulsarSuspendableClassifier] Auto-full mode, found suspendable Clojure protocol decl '%s:%s#%s(%s)'", nullToEmpty(sourceName), className, methodName, nullToEmpty(methodSignature));
                        return SuspendableType.SUSPENDABLE_SUPER;
                    } else if (isPossiblyProtocolMethodImplementation(interfaces)) {
                        db.log(LogLevel.DEBUG, "[PulsarSuspendableClassifier] Auto-full mode, found suspendable Clojure protocol impl '%s:%s#%s(%s)'", nullToEmpty(sourceName), className, methodName, nullToEmpty(methodSignature));
                        return SuspendableType.SUSPENDABLE;
                    } else if (isClojureUserFunction(className, superClassName, methodName)) {
                        db.log(LogLevel.DEBUG, "[PulsarSuspendableClassifier] Auto-full mode, found suspendable Clojure user function '%s:%s#%s(%s)'", nullToEmpty(sourceName), className, methodName, nullToEmpty(methodSignature));
                        return SuspendableType.SUSPENDABLE;
                    }
                }
            }
        }

        return null;
    }

    private static String nullToEmpty(final String s) {
        return s != null ? s : "";
    }

    private boolean autoInstrument() {
        return autoInstrumentAnonymousFunctionsOnly()
                || autoInstrumentEverythingClojure();
    }

    private boolean autoInstrumentAnonymousFunctionsOnly() {
        return autoInstrumentAnonymousFunctionsOnly;
    }

    private boolean autoInstrumentEverythingClojure() {
        return autoInstrumentEverythingClojure;
    }

    private boolean isPossiblyProtocolMethodDeclaration(final String sourceName, final boolean isInterface) {
        // Don't instrument all interfaces, only the ones we're sure they come from Clojure sources
        return isInterface && sourceName != null && sourceNameHasClojureExtension(sourceName);
    }

    private boolean isPossiblyClojureSourceName(final String sourceName) {
        // Considering even classes for which we don't have source info, e.g. because we're running Quasar without
        // extended info or for some other reason
        return sourceName == null || sourceNameHasClojureExtension(sourceName);
    }

    private boolean isPossiblyProtocolMethodImplementation(final String[] interfaces) {
        // TODO Avoid instrumenting protocol method impls whose base protocol interface is not SUSPENDABLE_SUPER this can happen for Java native protocols or protocols with no source info.
        HashSet<String> intersection = new HashSet<String>(Arrays.asList(interfaces));
        intersection.retainAll(CLOJURE_DATATYPE_INTERFACES);
        return !intersection.isEmpty();
    }

    private static boolean isClojureUserFunction(final String className, final String superClassName, final String methodName) {
        return isClojureFunctionClassName(className)
               && CLOJURE_FUNCTION_BASE_CLASSES.contains(superClassName)
               && (CLOJURE_FUNCTION_BASE_INVOCATION_METHODS.contains(methodName)
                  || CLOJURE_FUNCTION_ADDITIONAL_INVOCATION_METHODS.contains(methodName));
    }

    private static boolean isClojureFunctionClassName(final String className) {
        return className != null && className.contains(CLOJURE_FUNCTION_CLASS_NAME_MARKER);
    }

    private static boolean isClojureAnonymousFunctionClassName(final String className) {
        return className != null && className.contains(CLOJURE_FUNCTION_ANONYMOUS_CLASS_NAME_MARKER);
    }

    private boolean sourceNameHasClojureExtension(final String sourceName) {
        return sourceName.toLowerCase().endsWith(CLOJURE_SOURCE_EXTENSION);
    }
}
