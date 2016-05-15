/*
 * Pulsar: lightweight threads and Erlang-like actors for Clojure.
 * Copyright (C) 2013-2016, Parallel Universe Software Co. All rights reserved.
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
import co.paralleluniverse.fibers.instrument.SimpleSuspendableClassifier;
import co.paralleluniverse.fibers.instrument.SuspendableClassifier;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

public final class PulsarSuspendableClassifier implements SuspendableClassifier {
    private static final String CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_NAME = "co.paralleluniverse.pulsar.instrument.auto";
    private static final String CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_VALUE_ALL = "all";

    private final List<InstrumentMatcher[]> matchLists;
    private final boolean autoInstrumentEverythingClojure;
    private final SimpleSuspendableClassifier simpleClassifier;

    @Override
    public final SuspendableType isSuspendable(final MethodDatabase db, final String sourceName, final String sourceDebugInfo,
                                               final boolean isInterface, final String className, final String superClassName,
                                               final String[] interfaces, final String methodName, final String methodDesc,
                                               final String methodSignature, final String[] methodExceptions) {
        if (autoInstrumentEverythingClojure) {
            // First delegate to the resource files
            final SuspendableType st = simpleClassifier.isSuspendable(db, sourceName, sourceDebugInfo, isInterface, className, superClassName, interfaces, methodName, methodDesc, methodSignature, methodExceptions);
            if (st != null)
                return st;

            final InstrumentMatcher.Match<SuspendableType> t =
                match(db, matchLists, sourceName, sourceDebugInfo, isInterface, className, superClassName, interfaces,
                methodName, methodDesc, methodSignature, methodExceptions);
            if (t != null)
                return t.getValue();

            PulsarInstrumentListProvider.log(db, "auto", "evaluation of matchlist didn't say anything",
                                             sourceName, isInterface, className, superClassName, interfaces, methodName, methodSignature);
        } else if ((className.equals("clojure/lang/IFn") || className.startsWith("clojure/lang/IFn$")) && PulsarInstrumentListProvider.CLOJURE_FUNCTION_BASE_INVOCATION_METHODS.contains(methodName)) {
            return SuspendableType.SUSPENDABLE_SUPER;
        }

        return null;
    }

    public PulsarSuspendableClassifier(final ClassLoader classLoader) {
        this.autoInstrumentEverythingClojure =  CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_VALUE_ALL.equals(System.getProperty(CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_NAME));
        this.matchLists = loadMatchLists(classLoader);
        if (this.matchLists.size() == 0)
            this.matchLists.add(new PulsarInstrumentListProvider().getMatchList());
        this.simpleClassifier = new SimpleSuspendableClassifier(classLoader);
    }

    public PulsarSuspendableClassifier() {
        this(PulsarSuspendableClassifier.class.getClassLoader());
    }

    private static List<InstrumentMatcher[]> loadMatchLists(final ClassLoader classLoader) {
        final List<InstrumentMatcher[]> ret = new ArrayList<InstrumentMatcher[]>();
        final ServiceLoader<InstrumentListProvider> loader = ServiceLoader.load(InstrumentListProvider.class, classLoader);

        for (final InstrumentListProvider aLoader : loader)
            ret.add(aLoader.getMatchList());

        return ret;
    }

    private static InstrumentMatcher.Match<SuspendableType> match(final MethodDatabase db, final List<InstrumentMatcher[]> matchLists, final String sourceName, final String sourceDebugInfo,
                                                                 final boolean isInterface, final String className, final String superClassName, final String[] interfaces,
                                                                 final String methodName, final String methodDesc, final String methodSignature, final String[] methodExceptions) {
        for (final InstrumentMatcher[] ml : matchLists) {
            for (final InstrumentMatcher m : ml) {
                final InstrumentMatcher.Match<SuspendableType> t =
                        m.eval(db, sourceName, sourceDebugInfo, isInterface, className, superClassName, interfaces,
                                methodName, methodDesc, methodSignature, methodExceptions);
                if (t != null)
                    return t;
            }
        }
        return null;
    }
}
