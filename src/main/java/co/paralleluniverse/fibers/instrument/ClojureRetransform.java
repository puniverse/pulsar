/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.fibers.instrument;

import clojure.lang.AFunction;
import java.lang.instrument.UnmodifiableClassException;

/**
 *
 * @author pron
 */
public class ClojureRetransform {
    public void retransform(AFunction fn) throws UnmodifiableClassException {
        Retransform.retransform(fn.getClass());
    }
}
