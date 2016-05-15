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

import co.paralleluniverse.common.util.Action2;
import co.paralleluniverse.fibers.instrument.LogLevel;
import co.paralleluniverse.fibers.instrument.MethodDatabase;
import com.google.common.base.Predicate;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static co.paralleluniverse.fibers.instrument.MethodDatabase.*;

/**
 * @author circlespainter
 */
public final class PulsarInstrumentListProvider implements InstrumentListProvider {
    public static final List<String> CLOJURE_FUNCTION_BASE_INVOCATION_METHODS = Arrays.asList("invoke", "invokePrim", "applyTo", "invokeStatic");
    public static final List<String> CLOJURE_FUNCTION_ADDITIONAL_INVOCATION_METHODS = Arrays.asList("doInvoke", "applyToHelper", "call", "run");

    private static final String CLOJURE_PROXY_ANONYMOUS_CLASS_NAME_MARKER = "proxy$";
    private static final String CLOJURE_FUNCTION_CLASS_NAME_MARKER = "$";
    private static final List<String> CLOJURE_FUNCTION_BASE_CLASSES = Arrays.asList("clojure/lang/AFn", "clojure/lang/AFunction", "clojure/lang/RestFn", "clojure/lang/MultiFn");

    private static final String CLOJURE_SOURCE_EXTENSION = ".clj";
    private static final List<String> CLOJURE_DATATYPE_INTERFACES = Arrays.asList("clojure/lang/IObj", "clojure/lang/IType", "clojure/lang/IRecord");
    private static final List<String> CLOJURE_REDUCE_INTERFACES = Arrays.asList("clojure/lang/IReduce", "clojure/lang/IReduceInit");
    /** @noinspection unchecked*/
    private static final Predicate<String>[] CLOJURE_REDUCE_INTERFACES_P = new Predicate[] { eqN("clojure/lang/IReduce"), eqN("clojure/lang/IReduceInit") };

    @Override
    public InstrumentMatcher[] getMatchList() {
        final Predicate<String> srcP = endsWithN(CLOJURE_SOURCE_EXTENSION);

        final String testExamplePUMsg = "Pulsar's built-in matchlist found suspendable Parallel Universe Clojure test or example";

        final String cljSusFnCoreMsg = "Pulsar's built-in matchlist found suspendable Clojure RT for fn";
        final String puMsg = "Pulsar's built-in matchlist not saying anything about Parallel Universe";
        final String ccMsg = "Pulsar's built-in matchlist not saying anything about Clojure core";

        final String jdkOr3rdMsg = "Pulsar's built-in matchlist found NON suspendable JDK or other known non-suspendable 3rd-party";
        final String cljSusProxyMsg = "Pulsar's built-in matchlist found suspendable Clojure proxy method";
        final String cljSusProtoDefMsg = "Pulsar's built-in matchlist potentially found suspendable Clojure protocol def";
        final String cljSusFnMsg = "Pulsar's built-in matchlist found suspendable Clojure fn";
        final String cljSusProtoImplMsg = "Pulsar's built-in matchlist found suspendable Clojure protocol impl";
        final String susPUMeth = "Pulsar's built-in matchlist found suspendable Parallel Universe stack methods";

        /////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        // AUTO-INSTRUMENTATION GUIDELINES
        //
        // 1. Runtime efficiency: instrument as little as possible while still making unnecessary any suspendable-marking
        //    code.
        //    - Clojure runtime methods that implement language constructs have to be explicitly instrumented.
        //    - Some more Pulsar runtime methods have to be explicitly instrumented.
        //
        // 2. Instrumentation efficiency: evaluate rules as fast as possible (globally).
        //    - Rules likely to catch more methods should appear as close as possible to the top.
        //    - Unnecessary rules should be left out.
        //
        // 3. Maintenance: minimize the number of rules.
        /////////////////////////////////////////////////////////////////////////////////////////////////////////////////

        final Predicate<String> anyInvokePred = or(eqN("invoke"), eqN("invokeStatic"), eqN("invokePrim"), eqN("doInvoke"));

        return new InstrumentMatcher[] {
            // FAST BAILOUT: skip JDK and other known 3rd-party packages
            mClass(startsWithN("java/"), null, a(jdkOr3rdMsg)),
            mClass(startsWithN("jsr166e/"), null, a(jdkOr3rdMsg)),
            mClass(startsWithN("sun/"), null, a(jdkOr3rdMsg)),
            mClass(startsWithN("oracle/"), null, a(jdkOr3rdMsg)),
            mClass(startsWithN("com/oracle/"), null, a(jdkOr3rdMsg)),
            mClass(startsWithN("org/cliffc/high_scale_lib/"), null, a(jdkOr3rdMsg)),
            mClass(startsWithN("manifold/"), null, a(jdkOr3rdMsg)),
            mClass(startsWithN("gloss/"), null, a(jdkOr3rdMsg)),
            mClass(startsWithN("swiss/"), null, a(jdkOr3rdMsg)),
            mClass(startsWithN("potemkin/"), null, a(jdkOr3rdMsg)),

            // Clojure calls
            mClassAndMeth(startsWithN("clojure/lang/IFn"), or(eqN("invoke"), eqN("invokePrim")), SuspendableType.SUSPENDABLE_SUPER, a(cljSusFnCoreMsg)),
            mClassAndMeth(startsWithN("clojure/lang/MultiFn"), eqN("invoke"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
            mClassAndMeth(startsWithN("clojure/lang/AFunction$1"), eqN("doInvoke"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
            mClassAndMeth(startsWithN("clojure/lang/AFn"), eqN("applyTo"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
            mClassAndMeth(eqN("clojure/lang/Reflector"), startsWithN("invoke"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),

            // Clojure stdlib
            mClassAndMeth(eqN("clojure/core$reduce"), anyInvokePred, SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
            mClass(or(CLOJURE_REDUCE_INTERFACES_P), SuspendableType.SUSPENDABLE, a(cljSusFnMsg)),
            mIfsAndMeth (
                new Predicate<String[]>() {
                    @Override
                    public boolean apply(final String[] interfaces) {
                        final HashSet<String> intersection = new HashSet<String>(Arrays.asList(interfaces));
                        intersection.retainAll(CLOJURE_REDUCE_INTERFACES);
                        return !intersection.isEmpty();
                    }
                },
                startsWithN("reduce"),
                SuspendableType.SUSPENDABLE,
                a(cljSusProtoImplMsg)
            ),

            // FAST BAILOUT: don't change anything else about Clojure (skip rules below)
            mClass(startsWithN("clojure/"), null, a(ccMsg)),

            // PU
            mClassAndMeth(eqN("co/paralleluniverse/pulsar/InstrumentedIFn"), eqN("invoke"), SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(eqN("co/paralleluniverse/pulsar/core$kps_args"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(eqN("co/paralleluniverse/pulsar/core$join"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(eqN("co/paralleluniverse/pulsar/core$sleep"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(eqN("co/paralleluniverse/pulsar/core$join_STAR_"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(eqN("co/paralleluniverse/pulsar/core$rcv"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(eqN("co/paralleluniverse/pulsar/core$rcv_into"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(eqN("co/paralleluniverse/pulsar/core$snd"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(eqN("co/paralleluniverse/pulsar/core$snd_seq"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(eqN("co/paralleluniverse/pulsar/core$do_sel"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(eqN("co/paralleluniverse/pulsar/core$sel"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(eqN("co/paralleluniverse/pulsar/core$strampoline"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(eqN("co/paralleluniverse/pulsar/core$sleep"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(eqN("co/paralleluniverse/pulsar/actors$receive_timed"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(startsWithN("co/paralleluniverse/pulsar/actors$gen_fsm"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(startsWithN("co/paralleluniverse/pulsar/actors$child_spec"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(startsWithN("co/paralleluniverse/pulsar/actors$create_actor$"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(startsWithN("co/paralleluniverse/pulsar/async$put_BANG_"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(startsWithN("co/paralleluniverse/pulsar/async$take_BANG_"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(startsWithN("co/paralleluniverse/pulsar/async$_GT__BANG_"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(startsWithN("co/paralleluniverse/pulsar/async$f_to_chan"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(startsWithN("co/paralleluniverse/pulsar/async$_LT__BANG_"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(startsWithN("co/paralleluniverse/pulsar/async$last"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(startsWithN("co/paralleluniverse/pulsar/async$reduce"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(startsWithN("co/paralleluniverse/pulsar/async$pipe"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(startsWithN("co/paralleluniverse/pulsar/async$onto_chan"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(startsWithN("co/paralleluniverse/pulsar/async$rx_chan"), anyInvokePred, SuspendableType.SUSPENDABLE, a(susPUMeth)),
            mClassAndMeth(startsWithN("co/paralleluniverse/pulsar/dataflow$df_var"), eqN("deref"), SuspendableType.SUSPENDABLE, a(susPUMeth)),

            // Instrument proxies
            mClass(containsCIN("proxy$"), SuspendableType.SUSPENDABLE, a(cljSusFnMsg)),

            // Comsat Clojure
            mSrcAndClass(srcP, and(startsWithN("co/paralleluniverse/fiber/"), containsN("$")), SuspendableType.SUSPENDABLE, a(susPUMeth)),

            // Parallel Universe Clojure tests and examples
            mSrcAndClass(srcP, and(startsWithN("co/paralleluniverse"), or(containsCIN("test"), containsCIN("example"))), SuspendableType.SUSPENDABLE, a(testExamplePUMsg)),
            mSrcAndMeth(srcP, or(containsCIN("test"), containsCIN("example")), SuspendableType.SUSPENDABLE, a(testExamplePUMsg)),

            // FAST BAILOUT: don't change anything else about the rest of Parallel Universe (skip rules below)
            mClass(startsWithN("co/paralleluniverse/"), null, a(puMsg)),

            ////////////////////////
            // SUSPENDABLE USER CODE
            ////////////////////////

            // Instrument interfaces from .clj (proto defs)
            mSrcAndIsIf(srcP, eq(true), SuspendableType.SUSPENDABLE_SUPER, a(cljSusProtoDefMsg)),

            // Instrument proxy user methods
            mSrcAndClass(srcP, and(containsN(CLOJURE_PROXY_ANONYMOUS_CLASS_NAME_MARKER), countOccurrencesGTN("$", 1)), SuspendableType.SUSPENDABLE, a(cljSusProxyMsg)),

            // Instrument user functions
            mSrcAndClassAndSuperAndMeth (
                    srcP, containsN(CLOJURE_FUNCTION_CLASS_NAME_MARKER),
                    new Predicate<String>() {
                        @Override
                        public boolean apply(final String superClassName) {
                            return CLOJURE_FUNCTION_BASE_CLASSES.contains(superClassName);
                        }
                    },
                    or (
                        new Predicate<String>() {
                            @Override
                            public boolean apply(final String methodName) {
                                return CLOJURE_FUNCTION_BASE_INVOCATION_METHODS.contains(methodName);
                            }
                        },
                        new Predicate<String>() {
                            @Override
                            public boolean apply(final String methodName) {
                                return CLOJURE_FUNCTION_ADDITIONAL_INVOCATION_METHODS.contains(methodName);
                            }
                        }
                    ),
                    SuspendableType.SUSPENDABLE, a(cljSusFnMsg)
            ),

            // Instrument protocol implementations
            mSrcAndIfs (
                    srcP,
                    new Predicate<String[]>() {
                        @Override
                        public boolean apply(final String[] interfaces) {
                            final HashSet<String> intersection = new HashSet<String>(Arrays.asList(interfaces));
                            intersection.retainAll(CLOJURE_DATATYPE_INTERFACES);
                            return !intersection.isEmpty();
                        }
                    },
                    SuspendableType.SUSPENDABLE,
                    a(cljSusProtoImplMsg)
            ),
        };
    }

    public static void log(final MethodDatabase db, final String mode, final String message, final String sourceName,
                           final boolean isInterface, final String className, final String superClassName, final String[] interfaces,
                           final String methodName, final String methodSignature) {
        db.log(LogLevel.DEBUG, "[PulsarSuspendableClassifier] %s, %s '%s: %s %s[extends %s implements %s]#%s(%s)'",
                mode, message, sourceName != null ? sourceName : "<no source>", isInterface ? "interface" : "class",
                className, superClassName != null ? superClassName : "<no class>",
                interfaces != null ? Arrays.toString(interfaces) : "<no interface>",
                methodName, nullToEmpty(methodSignature));
    }

    private static Action2<InstrumentMatcher.EvalCriteria, InstrumentMatcher.Match<SuspendableType>> a(final String msg) {
        return new Action2<InstrumentMatcher.EvalCriteria, InstrumentMatcher.Match<SuspendableType>>() {
            @Override
            public void call(final InstrumentMatcher.EvalCriteria c, final InstrumentMatcher.Match<SuspendableType> t) {
                if (t != null)
                    log(c.db, "auto", msg + " (match type: '" + t + "')", c.sourceName, c.isInterface, c.className, c.superClassName, c.interfaces, c.methodName, c.methodSignature);
            }
        };
    }

    private static InstrumentMatcher mIfsAndMeth(final Predicate<String[]> interfacesP, final Predicate<String> methodNameP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, InstrumentMatcher.Match<SuspendableType>> a) {
        return new InstrumentMatcher(null, null, null, null, null, interfacesP, methodNameP, null, null, null, t, a);
    }

    private static InstrumentMatcher mSrcAndIfs(final Predicate<String> sourceP, final Predicate<String[]> interfacesP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, InstrumentMatcher.Match<SuspendableType>> a) {
        return new InstrumentMatcher(sourceP, null, null, null, null, interfacesP, null, null, null, null, t, a);
    }

    private static InstrumentMatcher mSrcAndClassAndSuperAndMeth(final Predicate<String> sourceP, final Predicate<String> classNameP, final Predicate<String> superClassNameP,
                                                                 final Predicate<String> methodNameP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, InstrumentMatcher.Match<SuspendableType>> a) {
        return new InstrumentMatcher(sourceP, null, null, classNameP, superClassNameP, null, methodNameP, null, null, null, t, a);
    }

    private static InstrumentMatcher mSrcAndClass(final Predicate<String> sourceP, final Predicate<String> classNameP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, InstrumentMatcher.Match<SuspendableType>> a) {
        return new InstrumentMatcher(sourceP, null, null, classNameP, null, null, null, null, null, null, t, a);
    }

    private static InstrumentMatcher mSrcAndMeth(final Predicate<String> sourceP, final Predicate<String> methodNameP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, InstrumentMatcher.Match<SuspendableType>> a) {
        return new InstrumentMatcher(sourceP, null, null, null, null, null, methodNameP, null, null, null, t, a);
    }

    private static InstrumentMatcher mSrcAndIsIf(final Predicate<String> sourceP, final Predicate<Boolean> isInterfaceP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, InstrumentMatcher.Match<SuspendableType>> a) {
        return new InstrumentMatcher(sourceP, null, isInterfaceP, null, null, null, null, null, null, null, t, a);
    }

    private static InstrumentMatcher mClass(final Predicate<String> classNameP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, InstrumentMatcher.Match<SuspendableType>> a) {
        return new InstrumentMatcher(null, null, null, classNameP, null, null, null, null, null, null, t, a);
    }

    private static InstrumentMatcher mClassAndMeth(final Predicate<String> classNameP, final Predicate<String> methodNameP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, InstrumentMatcher.Match<SuspendableType>> a) {
        return new InstrumentMatcher(null, null, null, classNameP, null, null, methodNameP, null, null, null, t, a);
    }

    private static Predicate<String> or(final Predicate<String>... ps) {
        return new Predicate<String>() {
            @Override
            public boolean apply(final String v) {
                boolean res = true;
                if (ps != null && ps.length > 0) {
                    res = false;
                    for (final Predicate<String> p : ps)
                        res |= p.apply(v);
                }
                return res;
            }
        };
    }

    private static Predicate<String> and(final Predicate<String>... ps) {
        return new Predicate<String>() {
            @Override
            public boolean apply(final String v) {
                boolean res = true;
                if (ps != null) {
                    for (final Predicate<String> p : ps)
                        res &= p.apply(v);
                }
                return res;
            }
        };
    }

    private static Predicate<String> countOccurrencesGTN(final String of, final int gt) {
        return new Predicate<String>() {
            @Override
            public boolean apply(final String v) {
                return of == null || (v != null && countOccurrences(of, v) > gt);
            }
        };
    }

    private static <X> Predicate<X> eq(final X spec) {
        return new Predicate<X>() {
            @Override
            public boolean apply(final X v) {
                return spec == v || (spec != null && spec.equals(v));
            }
        };
    }

    private static <X> Predicate<X> eqN(final X spec) {
        return new Predicate<X>() {
            @Override
            public boolean apply(final X v) {
                return spec == null || spec.equals(v);
            }
        };
    }

    private static Predicate<String> containsN(final String spec) {
        return new Predicate<String>() {
            @Override
            public boolean apply(final String v) {
                return spec == null || (v != null && v.contains(spec));
            }
        };
    }

    private static Predicate<String> containsCIN(final String spec) {
        return new Predicate<String>() {
            @Override
            public boolean apply(final String v) {
                return spec == null || (v != null && v.toLowerCase().contains(spec.toLowerCase()));
            }
        };
    }

    private static Predicate<String> startsWithN(final String spec) {
        return new Predicate<String>() {
            @Override
            public boolean apply(final String v) {
                return spec == null || (v != null && v.startsWith(spec));
            }
        };
    }

    private static Predicate<String> endsWithN(final String spec) {
        return new Predicate<String>() {
            @Override
            public boolean apply(final String v) {
                return spec == null || (v != null && v.endsWith(spec));
            }
        };
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
