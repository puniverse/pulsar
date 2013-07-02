/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.pulsar;

/**
 *
 * @author pron
 */
public class InstrumentedDelegator<T> implements IInstrumented {
    protected final T d;

    public InstrumentedDelegator(T d) {
        this.d = d;
    }
    
    
    @Override
    public int hashCode() {
        return d.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return d.equals(obj);
    }

    @Override
    public String toString() {
        return d.toString();
    }
}
