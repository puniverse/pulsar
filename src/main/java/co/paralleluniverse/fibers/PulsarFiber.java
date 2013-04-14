/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.fibers;

import co.paralleluniverse.strands.SuspendableCallable;
import jsr166e.ForkJoinPool;

/**
 *
 * @author pron
 */
public class PulsarFiber extends Fiber<Object> {
    public PulsarFiber(String name, ForkJoinPool fjPool, int stackSize, SuspendableCallable<Object> target) {
        super(name, fjPool, stackSize, target);
    }
}
