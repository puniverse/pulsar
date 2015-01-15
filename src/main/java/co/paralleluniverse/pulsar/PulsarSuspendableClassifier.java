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

    //////////////////////////////////////////////////////
    // Clojure auto-instrument support (EVAL/EXPERIMENTAL)
    //////////////////////////////////////////////////////

    public static final String CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_NAME = "co.paralleluniverse.pulsar.instrument.auto";
    public static final String CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_VALUE_ALL = "all";
    public static final String CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_VALUE_ANON = "anon";

    public static final String CLOJURE_FUNCTION_ANONYMOUS_CLASS_NAME_MARKER = "$fn__";
    public static final String CLOJURE_PROXY_ANONYMOUS_CLASS_NAME_MARKER = "proxy$";
    public static final String CLOJURE_FUNCTION_CLASS_NAME_MARKER = "$";
    public static final List<String> CLOJURE_FUNCTION_BASE_CLASSES = Arrays.asList("clojure/lang/AFn", "clojure/lang/AFunction", "clojure/lang/RestFn", "clojure/lang/MultiFn");
    public static final List<String> CLOJURE_FUNCTION_ADDITIONAL_INVOCATION_METHODS = Arrays.asList("doInvoke", "applyTo", "applyToHelper", "call", "run");

    private static final String CLOJURE_SOURCE_EXTENSION = ".clj";
    private static final List<String> CLOJURE_DATATYPE_INTERFACES = Arrays.asList("clojure/lang/IObj", "clojure/lang/IType", "clojure/lang/IRecord");
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
        //////////////////////////////////////////////////////
        // Clojure auto-instrument support (EVAL/EXPERIMENTAL)
        //////////////////////////////////////////////////////

        // Refs (based mostly on first two):
        //
        // - http://nicholaskariniemi.github.io/2014/01/26/clojure-compilation.html
        // - http://clojure.org/compilation
        // - https://github.com/clojure/clojure/blob/clojure-1.6.0/src/jvm/clojure/lang/Compiler.java#L3462
        // - https://github.com/clojure/clojure/blob/clojure-1.6.0/src/jvm/clojure/lang/Compiler.java#L2800
        // - https://github.com/pallet/ritz/blob/develop/repl-utils/src/ritz/repl_utils/mangle.clj

        if (autoInstrument()) {
            final SuspendableType declared =
                autoSuspendables.isSuspendable(className, methodName, methodDesc) ?
                    SuspendableType.SUSPENDABLE :
                        (autoSuspendables.isSuperSuspendable(className, methodName, methodDesc) ?
                            SuspendableType.SUSPENDABLE_SUPER : null);
            if (declared != null && declared != SuspendableType.NON_SUSPENDABLE) {
                log(db, "auto-?", "found explicit '" + declared + "'", sourceName, isInterface, className, superClassName, interfaces, methodName, methodSignature);
                return declared;
            }

            if (!isStdlib(className) && isPossiblyClojureSourceName(sourceName)) {
                if (autoInstrumentAnonymousFunctionsOnly()) {
                    if (isClojureAnonymousFunctionClassName(className)) {
                        log(db, "auto-anon", "found suspendable Clojure anon fn", sourceName, isInterface, className, superClassName, interfaces, methodName, methodSignature);
                        return SuspendableType.SUSPENDABLE;
                    }
                } else if (autoInstrumentEverythingClojure()) {
                    if (isPossiblyProtocolMethodDeclaration(sourceName, className, isInterface)) {
                        log(db, "auto-full", "found suspendable Clojure protocol decl", sourceName, isInterface, className, superClassName, interfaces, methodName, methodSignature);
                        return SuspendableType.SUSPENDABLE_SUPER;
                    } else if (isPossiblyProtocolMethodImplementation(sourceName, interfaces)) {
                        log(db, "auto-full", "found suspendable Clojure protocol impl", sourceName, isInterface, className, superClassName, interfaces, methodName, methodSignature);
                        return SuspendableType.SUSPENDABLE;
                    } else if (isClojureProxy(className)) {
                        log(db, "auto-full", "found suspendable Clojure proxy impl", sourceName, isInterface, className, superClassName, interfaces, methodName, methodSignature);
                        return SuspendableType.SUSPENDABLE;
                    } else if (isClojureUserFunction(className, superClassName, methodName)) {
                        log(db, "auto-full", "found suspendable Clojure user fn", sourceName, isInterface, className, superClassName, interfaces, methodName, methodSignature);
                        return SuspendableType.SUSPENDABLE;
                    }
                }
            }

            log(db, "auto-?", "NON suspendable", sourceName, isInterface, className, superClassName, interfaces, methodName, methodSignature);
        } else if (CLOJURE_FUNCTION_BASE_INTERFACES.contains(className) && CLOJURE_FUNCTION_BASE_INVOCATION_METHODS.contains(methodName))
            return SuspendableType.SUSPENDABLE_SUPER;

        return null;
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

    private static boolean isClojureProxy(final String className) {
        return className.contains(CLOJURE_PROXY_ANONYMOUS_CLASS_NAME_MARKER) && countOccurrences("$", className) > 1;
    }

    private static boolean isPossiblyProtocolMethodDeclaration(final String sourceName, final String className, final boolean isInterface) {
        // Don't instrument all interfaces, only the ones we're sure they come from Clojure sources

        // Assuming interface without sources outside of the Java and Parallel Universe packages are user protocol interfaces
        // TODO find a way to include (or get included) debug info in protocol classes
        return isInterface
                // && (sourceNameHasClojureExtension(sourceName) || !isStdlib(className))
                ;
    }

    private static boolean isStdlib(final String className) {
        return className.startsWith("java")
                || className.startsWith("clojure/lang")
                || className.startsWith("clojure/core");
    }

    private static boolean isPossiblyClojureSourceName(final String sourceName) {
        // Considering even classes for which we don't have source info, e.g. because we're running Quasar without
        // extended info or for some other reason
        return sourceName == null || sourceNameHasClojureExtension(sourceName);
    }

    private static boolean isPossiblyProtocolMethodImplementation(final String sourceName, final String[] interfaces) {
        HashSet<String> intersection = new HashSet<String>(Arrays.asList(interfaces));
        intersection.retainAll(CLOJURE_DATATYPE_INTERFACES);
        return !intersection.isEmpty() && sourceName != null && sourceNameHasClojureExtension(sourceName);
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

    private static boolean sourceNameHasClojureExtension(final String sourceName) {
        return sourceName!= null && sourceName.toLowerCase().endsWith(CLOJURE_SOURCE_EXTENSION);
    }

    private static void log(final MethodDatabase db, final String mode, final String message, final String sourceName,
                            final boolean isInterface, final String className, final String superClassName, final String[] interfaces,
                            final String methodName, final String methodSignature) {
        db.log(LogLevel.DEBUG, "[PulsarSuspendableClassifier] %s, %s '%s: %s %s[extends %s implements %s]#%s(%s)'",
            mode, message, sourceName != null ? sourceName : "<no source>", isInterface ? "interface" : "class",
            className, superClassName != null ? superClassName : "<no class>",
            interfaces != null ? Arrays.toString(interfaces) : "<no interface>",
            methodName, nullToEmpty(methodSignature));
    }

    private static int countOccurrences(final String of, final String in) {
        if (of == null) return -1;
        else if (in == null) return 0;
        else return (in.length() - in.replace(of, "").length()) / of.length();
    }

    private static String nullToEmpty(final String s) {
        return s != null ? s : "";
    }
}
