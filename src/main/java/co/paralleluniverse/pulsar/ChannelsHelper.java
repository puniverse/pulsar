/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.pulsar;

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.channels.Channel;
import co.paralleluniverse.strands.channels.DoubleChannel;
import co.paralleluniverse.strands.channels.FloatChannel;
import co.paralleluniverse.strands.channels.IntChannel;
import co.paralleluniverse.strands.channels.LongChannel;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author pron
 */
public class ChannelsHelper {
    public static void send(Channel<Object> channel, Object m) {
        channel.send(m);
    }

    public static Object receive(Channel<Object> channel) throws SuspendExecution, InterruptedException {
        return channel.receive();
    }

    public static Object receive(Channel<Object> channel, long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        return channel.receive(timeout, unit);
    }

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
