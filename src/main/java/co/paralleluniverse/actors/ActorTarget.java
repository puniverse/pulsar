package co.paralleluniverse.actors;

import co.paralleluniverse.fibers.SuspendExecution;

public interface ActorTarget<V> {
    /**
     * Entry point for LightweightThread execution.
     *
     * This method should never be called directly.
     *
     * @throws SuspendExecution This exception should never be caught
     */
    V run(Actor self) throws SuspendExecution, InterruptedException;
}
