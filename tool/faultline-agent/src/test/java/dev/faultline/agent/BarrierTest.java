package dev.faultline.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BarrierTest {

    @AfterEach
    void reset() {
        Barrier.resetForTest();
    }

    @Test
    void holdsUntilAnotherThreadPasses() throws Exception {
        long start = System.nanoTime();
        var held = CompletableFuture.supplyAsync(() -> Barrier.hold(5_000));

        Thread.sleep(200);
        assertThat(held).isNotDone();
        Barrier.passed();

        assertThat(held.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
    }

    @Test
    void givesUpWhenNobodyPasses() {
        long start = System.nanoTime();

        assertThat(Barrier.hold(150)).isFalse();
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(150);
    }

    @Test
    void aWorkerInAnotherProcessReleasesItWithADatagram() throws Exception {
        int port = freePort();
        Barrier.listen(port);
        var held = CompletableFuture.supplyAsync(() -> Barrier.hold(5_000));

        Thread.sleep(200);
        try (var partner = new DatagramSocket()) {
            partner.send(new DatagramPacket(new byte[] {1}, 1, new InetSocketAddress("127.0.0.1", port)));
        }

        assertThat(held.get(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void passesFromBeforeTheHoldDoNotCount() throws Exception {
        int port = freePort();
        Barrier.listen(port);
        try (var partner = new DatagramSocket()) {
            partner.send(new DatagramPacket(new byte[] {1}, 1, new InetSocketAddress("127.0.0.1", port)));
        }
        Barrier.passed();
        Thread.sleep(100);

        assertThat(Barrier.hold(200)).isFalse();
    }

    @Test
    void aPartnerSendsADatagramForEachPass() throws Exception {
        try (var holder = new DatagramSocket(0)) {
            holder.setSoTimeout(2_000);
            Barrier.signalTo("127.0.0.1:" + holder.getLocalPort());

            Barrier.passed();

            var packet = new DatagramPacket(new byte[16], 16);
            holder.receive(packet);
            assertThat(packet.getLength()).isEqualTo(1);
        }
    }

    private static int freePort() throws Exception {
        try (var s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }
}
