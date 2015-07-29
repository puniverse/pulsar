package co.paralleluniverse.continuation;

import clojure.lang.*;
import clojure.lang.Compiler;
import co.paralleluniverse.fibers.*;
import com.google.common.base.Function;


/**
 * @author pron
 */
public class PulsarContinuation extends ValuedContinuation<ContinuationScope, Object, Object, Object> implements IFn {
    private final Keyword scope;

    public PulsarContinuation(Keyword scope, boolean detached, int stackSize, final IFn target) {
        super(ContinuationScope.class, detached, stackSize, fnToCallable(target));
        this.scope = scope;
    }

    @Override
    protected void verifyScope(Suspend s) {
        super.verifyScope(s);
        ((ContinuationScope)s).verifyScope(scope);
    }

    public static Object pause(Keyword scope) throws ContinuationScope {
        return ValuedContinuation.pause(new ContinuationScope(scope));
    }

    public static Object pause(Keyword scope, Object arg) throws ContinuationScope {
        if (arg instanceof IFn)
            return ValuedContinuation.pause(new ContinuationScope(scope), PulsarContinuation.<Continuation<ContinuationScope, Object>,Object>fnToFunction((IFn) arg));
        else
            return ValuedContinuation.pause(new ContinuationScope(scope), arg);
    }

    public static Object pause(Keyword scope, Object val, IFn ccc) throws ContinuationScope {
        return ValuedContinuation.pause(new ContinuationScope(scope), val, fnToCalledCC(ccc));
    }

    public static Continuation<ContinuationScope, Object> suspend(Keyword scope, IFn ccc) throws ContinuationScope {
        return Continuation.suspend(new ContinuationScope(scope), fnToCalledCC(ccc));
    }

    public Object invoke() {
        run();
        return retval();
    }

    public Object invoke(Object arg) {
        run(arg);
        return retval();
    }

    private Object retval() {
        return self().isDone() ? self().getResult() : self().getPauseValue();
    }


    public static Callable<Object> fnToCallable(final IFn f) {
        return new Callable<Object>() {
            @Override
            public Object call() throws ContinuationScope {
                return f.invoke();
            }
        };
    }

    public static <F, T> Function<F, T> fnToFunction(final IFn f) {
        return new Function<F, T>() {
            @Override
            public T apply(F x) throws ContinuationScope {
                return (T) f.invoke(x);
            }
        };
    }

    public static CalledCC<ContinuationScope> fnToCalledCC(final IFn f) {
        return new CalledCC<ContinuationScope>() {
            @Override
            public <T> Continuation<ContinuationScope, T> suspended(Continuation<ContinuationScope, T> continuation) {
                return (Continuation<ContinuationScope, T>) f.invoke(continuation);
            }
        };
    }

//    public void run() {
//        invoke();
//    }

    public Object call() {
        return invoke();
    }

    public Object invoke(Object arg1, Object arg2) {
        return throwArity(2);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3) {
        return throwArity(3);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4) {
        return throwArity(4);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5) {
        return throwArity(5);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6) {
        return throwArity(6);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7) {
        return throwArity(7);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8) {
        return throwArity(8);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9) {
        return throwArity(9);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10) {
        return throwArity(10);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11) {
        return throwArity(11);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12) {
        return throwArity(12);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13) {
        return throwArity(13);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14) {
        return throwArity(14);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15) {
        return throwArity(15);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16) {
        return throwArity(16);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17) {
        return throwArity(17);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18) {
        return throwArity(18);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18, Object arg19) {
        return throwArity(19);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18, Object arg19, Object arg20) {
        return throwArity(20);
    }

    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18, Object arg19, Object arg20, Object... args) {
        return throwArity(21);
    }

    public Object applyTo(ISeq arglist) {
        return AFn.applyToHelper(this, Util.ret1(arglist, arglist = null));
    }

    public Object throwArity(int n) {
        String name = this.getClass().getSimpleName();
        throw new ArityException(n, Compiler.demunge(name));
    }
}
