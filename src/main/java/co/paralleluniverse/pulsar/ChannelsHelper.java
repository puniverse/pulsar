/*
 * Pulsar: lightweight threads and Erlang-like actors for Clojure.
 * Copyright (C) 2013-2014, Parallel Universe Software Co. All rights reserved.
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

/**
 * This class contains static methods to help Clojure avoid reflection for primitive channel operations.
 */
public class ChannelsHelper {
    public static void sendInt(IntChannel channel, int m) throws SuspendExecution, InterruptedException {
        channel.send(m);
    }

    public static boolean trySendInt(IntChannel channel, int m) {
        return channel.trySend(m);
    }

    public static void sendLong(LongChannel channel, long m) throws SuspendExecution, InterruptedException {
        channel.send(m);
    }

    public static boolean trySendLong(LongChannel channel, long m) {
        return channel.trySend(m);
    }

    public static void sendFloat(FloatChannel channel, float m) throws SuspendExecution, InterruptedException {
        channel.send(m);
    }

    public static boolean trySendFloat(FloatChannel channel, float m) {
        return channel.trySend(m);
    }

    public static void sendDouble(DoubleChannel channel, double m) throws SuspendExecution, InterruptedException {
        channel.send(m);
    }

    public static boolean trySendDouble(DoubleChannel channel, double m) {
        return channel.trySend(m);
    }
}
