/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.fibers.instrument;

import clojure.lang.AFunction;
import clojure.lang.RestFn;
import org.objectweb.asm.Type;

/**
 *
 * @author pron
 */
public class PulsarSuspendable implements SuspendableClassifier {
    private static final String AFUNCTION_NAME = Type.getInternalName(AFunction.class);
    private static final String RESTFN_NAME = Type.getInternalName(RestFn.class);
    
    @Override
    public boolean isSuspendable(boolean retransform, String className, String superClassName, String[] interfaces, String methodName, String methodDesc, String methodSignature, String[] methodExceptions) {
        if(!retransform)
            return false;
        final boolean res = (AFUNCTION_NAME.equals(superClassName) || RESTFN_NAME.equals(superClassName)) && ("invoke".equals(methodName) || "doInvoke".equals(methodName));
        System.err.println("Returned " + res + " for " + className + "." + methodName + methodDesc);
        return res;
    }
    
}
