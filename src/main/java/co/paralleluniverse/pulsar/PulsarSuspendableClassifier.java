/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.pulsar;

import co.paralleluniverse.fibers.instrument.SuspendableClassifier;
import org.objectweb.asm.Type;

/**
 *
 * @author pron
 */
public class PulsarSuspendableClassifier implements SuspendableClassifier {
    static final String EXCEPTION_NAME = Type.getInternalName(SneakySuspendExecution.class);
    @Override
    public boolean isSuspendable(String className, String superClassName, String[] interfaces, String methodName, String methodDesc, String methodSignature, String[] methodExceptions) {
        final boolean res = checkExceptions(methodExceptions);
        return res;
    }

    private static boolean checkExceptions(String[] exceptions) {
        if (exceptions != null) {
            for (String ex : exceptions) {
                if (ex.equals(EXCEPTION_NAME))
                    return true;
            }
        }
        return false;
    }
}
