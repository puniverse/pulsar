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
import co.paralleluniverse.fibers.instrument.MethodDatabase;
import co.paralleluniverse.fibers.instrument.MethodDatabase.SuspendableType;
import co.paralleluniverse.fibers.instrument.SuspendableClassifier;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

public class PulsarSuspendableClassifier implements SuspendableClassifier {
    private static final String CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_NAME = "co.paralleluniverse.pulsar.instrument.auto";
    private static final String CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_VALUE_ALL = "all";

    private final List<InstrumentMatcher[]> matchLists = new ArrayList<InstrumentMatcher[]>();
    private final ServiceLoader<InstrumentListProvider> loader;
    private final boolean autoInstrumentEverythingClojure;

    @Override
    public SuspendableType isSuspendable(final MethodDatabase db, final String sourceName, final String sourceDebugInfo,
                                         final boolean isInterface, final String className, final String superClassName,
                                         final String[] interfaces, final String methodName, final String methodDesc,
                                         final String methodSignature, final String[] methodExceptions) {
        if (autoInstrumentEverythingClojure) {
            //////////////////////////////////////////////////////
            // Clojure auto-instrument support (EVAL/EXPERIMENTAL)
            //////////////////////////////////////////////////////

            final SuspendableType t = match(db, sourceName, sourceDebugInfo, isInterface, className, superClassName, interfaces, methodName, methodDesc, methodSignature, methodExceptions);
            if (t != null)
                return t;

            PulsarInstrumentListProvider.log(db, "auto", "evaluation of matchlist didn't say anything",
                                             sourceName, isInterface, className, superClassName, interfaces, methodName, methodSignature);
        } else if (PulsarInstrumentListProvider.CLOJURE_FUNCTION_BASE_INTERFACES.contains(className) && PulsarInstrumentListProvider.CLOJURE_FUNCTION_BASE_INVOCATION_METHODS.contains(methodName)) {
            return SuspendableType.SUSPENDABLE_SUPER;
        }

        return null;
    }

    private SuspendableType match(final MethodDatabase db, final String sourceName, final String sourceDebugInfo,
                                  final boolean isInterface, final String className, final String superClassName, final String[] interfaces,
                                  final String methodName, final String methodDesc, final String methodSignature, final String[] methodExceptions) {
        for (final InstrumentMatcher[] ml : matchLists) {
            for (final InstrumentMatcher m : ml) {
                final SuspendableType t = m.eval(db, sourceName, sourceDebugInfo, isInterface, className, superClassName, interfaces, methodName, methodDesc, methodSignature, methodExceptions);
                if (t != null)
                    return t;
            }
        }
        return null;
    }

    public PulsarSuspendableClassifier(final ClassLoader classLoader) {
        this.autoInstrumentEverythingClojure =  CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_VALUE_ALL.equals(System.getProperty(CLOJURE_AUTO_INSTRUMENT_STRATEGY_SYSTEM_PROPERTY_NAME));
        this.loader = ServiceLoader.load(InstrumentListProvider.class, classLoader);

        final Iterator<InstrumentListProvider> i = this.loader.iterator();
        while (i.hasNext()) {
            this.matchLists.add(i.next().getMatchList());
        }

        if (this.matchLists.size() == 0)
            this.matchLists.add(new PulsarInstrumentListProvider().getMatchList());
    }

    public PulsarSuspendableClassifier() {
        this(PulsarSuspendableClassifier.class.getClassLoader());
    }
}
