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
public class PulsarInstrumentListProvider implements InstrumentListProvider {
    public static final List<String> CLOJURE_FUNCTION_BASE_INTERFACES = Arrays.asList("clojure/lang/IFn", "clojure/lang/IFn$");
    public static final List<String> CLOJURE_FUNCTION_BASE_INVOCATION_METHODS = Arrays.asList("invoke", "invokePrim");

    private static final String CLOJURE_PROXY_ANONYMOUS_CLASS_NAME_MARKER = "proxy$";
    private static final String CLOJURE_FUNCTION_CLASS_NAME_MARKER = "$";
    private static final List<String> CLOJURE_FUNCTION_BASE_CLASSES = Arrays.asList("clojure/lang/AFn", "clojure/lang/AFunction", "clojure/lang/RestFn", "clojure/lang/MultiFn");
    private static final List<String> CLOJURE_FUNCTION_ADDITIONAL_INVOCATION_METHODS = Arrays.asList("doInvoke", "applyTo", "applyToHelper", "call", "run");

    private static final String CLOJURE_SOURCE_EXTENSION = ".clj";
    private static final List<String> CLOJURE_DATATYPE_INTERFACES = Arrays.asList("clojure/lang/IObj", "clojure/lang/IType", "clojure/lang/IRecord");

    @Override
    public InstrumentMatcher[] getMatchList() {
        final Predicate<String> srcP = or(eq((String) null), endsWithN(CLOJURE_SOURCE_EXTENSION));

        final String testExamplePUMsg = "Pulsar's built-in matchlist found suspendable Parallel Universe Clojure test or example";

        final String cljSusFnCoreMsg = "Pulsar's built-in matchlist found suspendable Clojure RT for fn";
        final String cljSusLsCoreMsg = "Pulsar's built-in matchlist found suspendable Clojure RT for lazyseq";
        final String cljSusProtoSusCoreMsg = "Pulsar's built-in matchlist found suspendable Clojure RT for protocol extension";

        final String jdkMsg = "Pulsar's built-in matchlist found NON suspendable JDK";
        final String puMsg = "Pulsar's built-in matchlist not saying anything about Parallel Universe";
        final String cljCoreMsg = "Pulsar's built-in matchlist found NON suspendable Clojure Core RT";
        final String cljUtilMsg = "Pulsar's built-in matchlist found NON suspendable Clojure Utils RT";
        final String cljModMsg = "Pulsar's built-in matchlist found NON suspendable Clojure module loading RT";
        final String specialMsg = "Pulsar's built-in matchlist found NON suspendable special method";
        final String tooMsg = "Pulsar's built-in matchlist found NON suspendable too large method";
        final String cljTyMsg = "Pulsar's built-in matchlist found NON suspendable Clojure deftype RT";
        final String cljRPMsg = "Pulsar's built-in matchlist found NON suspendable Clojure reify/proxy RT";
        final String cljProxyMsg = "Pulsar's built-in matchlist found NON suspendable Clojure proxy RT";
        final String cljRecMsg = "Pulsar's built-in matchlist found NON suspendable Clojure defrecord RT";

        final String cljSusProxyMsg = "Pulsar's built-in matchlist found suspendable Clojure proxy method";
        final String cljSusProtoDefMsg = "Pulsar's built-in matchlist potentially found suspendable Clojure protocol def";
        final String cljSusFnMsg = "Pulsar's built-in matchlist found suspendable Clojure fn";
        final String cljSusProtoImplMsg = "Pulsar's built-in matchlist found suspendable Clojure protocol impl";

        return new InstrumentMatcher[] {
                // Already in suspendables but explicitly marking them suspendable as the rest of clojure/lang is marked NOT suspendable later
                mClassAndMeth(eqN("clojure/lang/Reflector"), eqN("invokeNoArgInstanceMember"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/Reflector"), eqN("invokeInstanceMethod"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/Reflector"), eqN("invokeMatchingMethod"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/Reflector"), eqN("invokeStaticMethod"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/Reflector"), eqN("invokeStaticMethod"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),

                // Instrument function calls
                mClassAndMeth(eqN("clojure/lang/IFn"), eqN("invoke"), SuspendableType.SUSPENDABLE_SUPER, a(cljSusFnCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/IFn"), eqN("invokePrim"), SuspendableType.SUSPENDABLE_SUPER, a(cljSusFnCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/MultiFn"), eqN("invoke"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/AFunction"), eqN("call"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/AFunction"), eqN("invoke"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/AFunction"), eqN("applyTo"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/AFunction"), eqN("applyToHelper"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/RestFn"), eqN("doInvoke"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/RestFn"), eqN("applyTo"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/RT"), eqN("first"), SuspendableType.SUSPENDABLE, a(cljSusFnCoreMsg)),

                // Instrument lazy seqs
                mClassAndMeth(eqN("clojure/lang/LazySeq"), eqN("first"), SuspendableType.SUSPENDABLE, a(cljSusLsCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/LazySeq"), eqN("seq"), SuspendableType.SUSPENDABLE, a(cljSusLsCoreMsg)),
                mClassAndMeth(eqN("clojure/lang/LazySeq"), eqN("sval"), SuspendableType.SUSPENDABLE, a(cljSusLsCoreMsg)),

                // Instrument protocol extension
                mClassAndMeth(eqN("clojure/core$_cache_protocol_fn"), eqN("invoke"), SuspendableType.SUSPENDABLE, a(cljSusProtoSusCoreMsg)),
                mClassAndMeth(eqN("clojure/core$expand_method_impl_cache"), eqN("invoke"), SuspendableType.SUSPENDABLE, a(cljSusProtoSusCoreMsg)),
                mClassAndMeth(eqN("clojure/core$maybe_min_hash"), eqN("invoke"), SuspendableType.SUSPENDABLE, a(cljSusProtoSusCoreMsg)),
                mClassAndMeth(eqN("clojure/core$first"), eqN("invoke"), SuspendableType.SUSPENDABLE, a(cljSusProtoSusCoreMsg)),

                // Skip JDK
                mClass(startsWithN("java"), SuspendableType.NON_SUSPENDABLE, a(jdkMsg)),

                // Instrument Parall Universe Clojure tests and examples
                mSrcAndClass(srcP, and(startsWithN("co/paralleluniverse"), or(containsCIN("test"), containsCIN("example"))), SuspendableType.SUSPENDABLE, a(testExamplePUMsg)),
                mSrcAndMeth(srcP, or(containsCIN("test"), containsCIN("example")), SuspendableType.SUSPENDABLE, a(testExamplePUMsg)),
                // Don't say anything about the rest of Parallel Universe
                mClass(startsWithN("co/paralleluniverse"), null, a(puMsg)),

                // Skip Clojure core
                mClass(startsWithN("clojure/lang"), SuspendableType.NON_SUSPENDABLE, a(cljCoreMsg)),
                mClass(startsWithN("clojure/core"), SuspendableType.NON_SUSPENDABLE, a(cljCoreMsg)),
                // mClass(startsWithN("clojure/java"), SuspendableType.NON_SUSPENDABLE, a(cljCoreMsg)), // Ring needs this instrumented
                mClass(startsWithN("clj_tuple"), SuspendableType.NON_SUSPENDABLE, a(cljCoreMsg)),
                mClass(startsWithN("clojure/set"), SuspendableType.NON_SUSPENDABLE, a(cljCoreMsg)),
                mClass(startsWithN("clojure/string"), SuspendableType.NON_SUSPENDABLE, a(cljCoreMsg)),
                mClass(startsWithN("clojure/uuid"), SuspendableType.NON_SUSPENDABLE, a(cljCoreMsg)),
                mClass(startsWithN("clojure/instant"), SuspendableType.NON_SUSPENDABLE, a(cljCoreMsg)),
                mClass(startsWithN("clojure/main"), SuspendableType.NON_SUSPENDABLE, a(cljCoreMsg)),

                // Skip Clojure utils
                mClass(startsWithN("clojure/pprint"), SuspendableType.NON_SUSPENDABLE, a(cljUtilMsg)),
                mClass(startsWithN("clojure/tools/logging"), SuspendableType.NON_SUSPENDABLE, a(cljUtilMsg)),
                mClass(startsWithN("clojure/walk"), SuspendableType.NON_SUSPENDABLE, a(cljUtilMsg)),
                mClass(startsWithN("clojure/test"), SuspendableType.NON_SUSPENDABLE, a(cljUtilMsg)),
                mClass(startsWithN("clojure/tools/analyzer"), SuspendableType.NON_SUSPENDABLE, a(cljUtilMsg)),

                // Skip module loading
                mClass(containsN("$loading__"), SuspendableType.NON_SUSPENDABLE, a(cljModMsg)),

                // Skip special methods
                mMeth(startsWithN("<"), SuspendableType.NON_SUSPENDABLE, a(specialMsg)),

                // Skip too large methods
                mClass(startsWithN("co/paralleluniverse/pulsar/actors$spawn"), SuspendableType.NON_SUSPENDABLE, a(tooMsg)),
                mClass(startsWithN("co/paralleluniverse/pulsar/actors$receive"), SuspendableType.NON_SUSPENDABLE, a(tooMsg)),

                // Skip technical deftype methods
                mMethAndIfs(startsWithN("getBasis"), arrayContainsN("clojure/lang/IType"), SuspendableType.NON_SUSPENDABLE, a(cljTyMsg)),

                // Skip technical proxy/reify methods
                mMethAndIfs(startsWithN("meta"), arrayContainsN("clojure/lang/IObj"), SuspendableType.NON_SUSPENDABLE, a(cljRPMsg)),
                mMethAndIfs(startsWithN("withMeta"), arrayContainsN("clojure/lang/IObj"), SuspendableType.NON_SUSPENDABLE, a(cljRPMsg)),

                // Skip technical proxy methods
                mMethAndIfs(startsWithN("__initClojureFnMappings"), arrayContainsN("clojure/lang/IProxy"), SuspendableType.NON_SUSPENDABLE, a(cljProxyMsg)),
                mMethAndIfs(startsWithN("__updateClojureFnMappings"), arrayContainsN("clojure/lang/IProxy"), SuspendableType.NON_SUSPENDABLE, a(cljProxyMsg)),
                mMethAndIfs(startsWithN("__getClojureFnMappings"), arrayContainsN("clojure/lang/IProxy"), SuspendableType.NON_SUSPENDABLE, a(cljProxyMsg)),

                // Skip technical record methods
                mMethAndIfs(startsWithN("getBasis"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("create"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("hasheq"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("hashCode"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("equals"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("meta"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("withMeta"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("valAt"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("getLookupThunk"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("count"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("empty"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("cons"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("equiv"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("containsKey"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("entryAt"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("seq"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("iterator"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("assoc"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("without"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("size"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("isEmpty"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("containsValue"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("get"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("put"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("remove"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("putAll"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("clear"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("keySey"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("values"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("entrySet"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),
                mMethAndIfs(startsWithN("assoc"), arrayContainsN("clojure/lang/IRecord"), SuspendableType.NON_SUSPENDABLE, a(cljRecMsg)),

                // Instrument interfaces from .clj or no source: missing better info, assuming they are all protocols
                // TODO find a way to include (or get included) debug info in protocol classes
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
                        SuspendableType.SUSPENDABLE_SUPER,
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

    private static Action2<InstrumentMatcher.EvalCriteria, MethodDatabase.SuspendableType> a(final String msg) {
        return new Action2<InstrumentMatcher.EvalCriteria, MethodDatabase.SuspendableType>() {
            @Override
            public void call(final InstrumentMatcher.EvalCriteria c, final SuspendableType t) {
                if (t != null)
                    log(c.db, "auto", msg, c.sourceName, c.isInterface, c.className, c.superClassName, c.interfaces, c.methodName, c.methodSignature);
            }
        };
    }

    private static InstrumentMatcher mSrcAndIfs(final Predicate<String> sourceP, final Predicate<String[]> interfacesP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, MethodDatabase.SuspendableType> a) {
        return new InstrumentMatcher(sourceP, null, null, null, null, interfacesP, null, null, null, null, t, a);
    }

    private static InstrumentMatcher mSrcAndClassAndSuperAndMeth(final Predicate<String> sourceP, final Predicate<String> classNameP, final Predicate<String> superClassNameP,
                                                                 final Predicate<String> methodNameP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, MethodDatabase.SuspendableType> a) {
        return new InstrumentMatcher(sourceP, null, null, classNameP, superClassNameP, null, methodNameP, null, null, null, t, a);
    }

    private static InstrumentMatcher mSrcAndClass(final Predicate<String> sourceP, final Predicate<String> classNameP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, MethodDatabase.SuspendableType> a) {
        return new InstrumentMatcher(sourceP, null, null, classNameP, null, null, null, null, null, null, t, a);
    }

    private static InstrumentMatcher mSrcAndMeth(final Predicate<String> sourceP, final Predicate<String> methodNameP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, MethodDatabase.SuspendableType> a) {
        return new InstrumentMatcher(sourceP, null, null, null, null, null, methodNameP, null, null, null, t, a);
    }

    private static InstrumentMatcher mSrcAndIsIf(final Predicate<String> sourceP, final Predicate<Boolean> isInterfaceP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, MethodDatabase.SuspendableType> a) {
        return new InstrumentMatcher(sourceP, null, isInterfaceP, null, null, null, null, null, null, null, t, a);
    }

    private static InstrumentMatcher mMethAndIfs(final Predicate<String> methodNameP, final Predicate<String[]> interfacesP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, MethodDatabase.SuspendableType> a) {
        return new InstrumentMatcher(null, null, null, null, null, interfacesP, methodNameP, null, null, null, t, a);
    }

    private static InstrumentMatcher mClass(final Predicate<String> classNameP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, MethodDatabase.SuspendableType> a) {
        return new InstrumentMatcher(null, null, null, classNameP, null, null, null, null, null, null, t, a);
    }

    private static InstrumentMatcher mClassAndMeth(final Predicate<String> classNameP, final Predicate<String> methodNameP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, MethodDatabase.SuspendableType> a) {
        return new InstrumentMatcher(null, null, null, classNameP, null, null, methodNameP, null, null, null, t, a);
    }

    private static InstrumentMatcher mMeth(final Predicate<String> methodNameP, final SuspendableType t, final Action2<InstrumentMatcher.EvalCriteria, MethodDatabase.SuspendableType> a) {
        return new InstrumentMatcher(null, null, null, null, null, null, methodNameP, null, null, null, t, a);
    }

    private static Predicate<String> or(final Predicate<String> p1, final Predicate<String> p2) {
        return new Predicate<String>() {
            @Override
            public boolean apply(final String v) {
                return p1.apply(v) || p2.apply(v);
            }
        };
    }

    private static Predicate<String> and(final Predicate<String> p1, final Predicate<String> p2) {
        return new Predicate<String>() {
            @Override
            public boolean apply(final String v) {
                return p1.apply(v) && p2.apply(v);
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

    private static Predicate<String[]> arrayContainsN(final String spec) {
        return new Predicate<String[]>() {
            @Override
            public boolean apply(final String[] v) {
                for (final String s : v) {
                    if (s != null && s.equals(spec))
                        return true;
                }
                return false;
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
