/*
 * Pulsar: lightweight threads and Erlang-like actors for Clojure.
 * Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
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

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.channels.DoubleChannel;
import co.paralleluniverse.strands.channels.FloatChannel;
import co.paralleluniverse.strands.channels.IntChannel;
import co.paralleluniverse.strands.channels.LongChannel;
import java.util.concurrent.TimeUnit;

/**
 * This class contains static methods to help Clojure avoid reflection for primitive channel operations.
 */
public class ChannelsHelper {
    public static void sendInt(IntChannel channel, int m) {
        channel.send(m);
    }

    public static int receiveInt(IntChannel channel) throws SuspendExecution, InterruptedException {
        return channel.receiveInt();
    }

    public static int receiveInt(IntChannel channel, long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        return channel.receiveInt(timeout, unit);
    }

    public static void sendLong(LongChannel channel, long m) {
        channel.send(m);
    }

    public static long receiveLong(LongChannel channel) throws SuspendExecution, InterruptedException {
        return channel.receiveLong();
    }

    public static long receiveLong(LongChannel channel, long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        return channel.receiveLong(timeout, unit);
    }

    public static void sendFloat(FloatChannel channel, float m) {
        channel.send(m);
    }

    public static float receiveFloat(FloatChannel channel) throws SuspendExecution, InterruptedException {
        return channel.receiveFloat();
    }

    public static float receiveFloat(FloatChannel channel, long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        return channel.receiveFloat(timeout, unit);
    }

    public static void sendDouble(DoubleChannel channel, double m) {
        channel.send(m);
    }

    public static double receiveDouble(DoubleChannel channel) throws SuspendExecution, InterruptedException {
        return channel.receiveDouble();
    }

    public static double receiveDouble(DoubleChannel channel, long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        return channel.receiveDouble(timeout, unit);
    }
}
