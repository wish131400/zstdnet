package cn.tohsaka.factory.zstdnet.proxy;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LocalZstdNetDirectUdpTest {

    @Test
    void directUdpRouteForwardsToTheOriginalServerPort() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket echoServer = new DatagramSocket(0, loopback)) {
            echoServer.setSoTimeout(5000);
            AtomicReference<Exception> echoFailure = new AtomicReference<>();
            Thread echoThread = new Thread(() -> echoOnce(echoServer, echoFailure), "zstdnet-test-direct-udp-echo");
            echoThread.setDaemon(true);
            echoThread.start();

            int localPort = findFreeUdpPort(loopback);
            try (LocalZstdNet.UdpProxyHandle route = LocalZstdNet.startDirectUdpForwarder(
                "127.0.0.1",
                echoServer.getLocalPort(),
                localPort
            ); DatagramSocket client = new DatagramSocket()) {
                client.setSoTimeout(5000);
                byte[] payload = "svc-secret".getBytes(StandardCharsets.UTF_8);
                client.send(new DatagramPacket(payload, payload.length, loopback, localPort));

                byte[] received = new byte[payload.length];
                DatagramPacket response = new DatagramPacket(received, received.length);
                client.receive(response);
                assertArrayEquals(payload, Arrays.copyOf(response.getData(), response.getLength()));
            }

            echoThread.join(1000);
            assertNull(echoFailure.get());
        }
    }

    private static int findFreeUdpPort(InetAddress loopback) throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, loopback)) {
            return socket.getLocalPort();
        }
    }

    private static void echoOnce(DatagramSocket socket, AtomicReference<Exception> failure) {
        try {
            byte[] buffer = new byte[256];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            socket.send(new DatagramPacket(packet.getData(), packet.getLength(), packet.getSocketAddress()));
        } catch (Exception e) {
            failure.set(e);
        }
    }
}
