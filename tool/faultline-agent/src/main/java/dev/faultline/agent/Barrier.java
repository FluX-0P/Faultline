package dev.faultline.agent;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds a thread at the fault point until someone else has passed the plan's {@code until} point, so a race between
 * two workers happens every run instead of once in a while. Another thread of the same app counts, and so does another
 * process: its agent sends a small UDP datagram each time it passes the point, to the port the holding agent listens on.
 */
public final class Barrier {
    private static final int POLL_MS = 10;
    private static final AtomicLong passes = new AtomicLong();
    private static volatile DatagramSocket inbox;
    private static volatile DatagramSocket outbox;
    private static volatile String partnerHost;
    private static volatile int partnerPort;

    private Barrier() {}

    /** The holding agent: partners in other processes signal to this port. */
    static void listen(int port) throws SocketException {
        inbox = new DatagramSocket(port);
    }

    /** A partner's agent: each pass of the until point is sent to host:port. */
    static void signalTo(String hostAndPort) throws SocketException {
        int colon = hostAndPort.lastIndexOf(':');
        partnerHost = hostAndPort.substring(0, colon);
        partnerPort = Integer.parseInt(hostAndPort.substring(colon + 1));
        outbox = new DatagramSocket();
    }

    /** Called from the advice copied into the until method, so it has to stay public. */
    public static void passed() {
        passes.incrementAndGet();
        DatagramSocket out = outbox;
        if (out == null) return;
        try {
            var to = new InetSocketAddress(partnerHost, partnerPort);   // resolved through the JVM's DNS cache
            if (!to.isUnresolved()) out.send(new DatagramPacket(new byte[] {1}, 1, to));
        } catch (IOException e) {
            // the holder is not there (restarting, say): nobody is waiting for this pass
        }
    }

    /** Waits until another thread or process passes the until point, at most {@code timeoutMs}. True if one did. */
    static synchronized boolean hold(long timeoutMs) {
        long before = passes.get();
        DatagramSocket in = inbox;
        if (in != null) drain(in);
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        var packet = new DatagramPacket(new byte[16], 16);
        try {
            while (passes.get() == before) {
                long left = (deadline - System.nanoTime()) / 1_000_000;
                if (left <= 0) return false;
                if (in == null) {
                    Thread.sleep(Math.min(left, POLL_MS));
                    continue;
                }
                in.setSoTimeout((int) Math.max(1, Math.min(left, POLL_MS)));
                try {
                    in.receive(packet);
                    return true;
                } catch (SocketTimeoutException e) {
                    // nothing yet; look at this app's own threads again
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            FaultAgent.log("barrier stopped waiting: " + e);
            return false;
        }
    }

    /** Throws away signals of passes that happened before this hold began. */
    private static void drain(DatagramSocket in) {
        var packet = new DatagramPacket(new byte[16], 16);
        try {
            in.setSoTimeout(1);
            while (true) in.receive(packet);
        } catch (IOException e) {
            // empty now
        }
    }

    /** Tests start each case from scratch. */
    static void resetForTest() {
        if (inbox != null) inbox.close();
        if (outbox != null) outbox.close();
        inbox = null;
        outbox = null;
        passes.set(0);
    }
}
