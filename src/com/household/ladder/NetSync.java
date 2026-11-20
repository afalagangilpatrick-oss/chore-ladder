package com.household.ladder;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The second transport: phone to phone over the tailnet, for when they are not
 * in the same room. Same shape as the Bluetooth one — both phones listen and
 * dial at once, first connection wins, each side swaps its whole event log.
 *
 * This never talks to a server. It connects directly to the other phone's
 * tailnet address, which WireGuard has already authenticated and encrypted.
 * Two guards on top of that, because the phone may also be sitting on a cafe
 * network at the time:
 *   - the configured peer must be inside 100.64.0.0/10, the tailnet range
 *   - an inbound connection is dropped unless it comes from that exact address
 * So an open port on a public network answers nobody.
 */
public class NetSync {

    public static final int PORT = 47713;
    private static final int ACCEPT_TIMEOUT_MS = 40000;
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int MAX_PAYLOAD = 8 * 1024 * 1024;

    public interface Result {
        void ok(String peerPayload, String peerName);
        void fail(String reason);
    }

    /** 100.64.0.0/10 — the CGNAT range Tailscale hands out. */
    public static boolean looksLikeTailnet(String ip) {
        try {
            String[] p = ip.trim().split("\\.");
            if (p.length != 4) return false;
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 100 && b >= 64 && b <= 127;
        } catch (Throwable t) { return false; }
    }

    public static void exchange(final String peerIp, final String payload, final Result cb) {
        if (peerIp == null || peerIp.trim().isEmpty()) {
            cb.fail("No tailnet address set for the other phone.");
            return;
        }
        final String peer = peerIp.trim();
        if (!looksLikeTailnet(peer)) {
            cb.fail("That does not look like a tailnet address. It should start with 100.");
            return;
        }

        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicReference<ServerSocket> server = new AtomicReference<>();

        Thread listen = new Thread(() -> {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                ss.setSoTimeout(ACCEPT_TIMEOUT_MS);
                server.set(ss);
                while (!done.get()) {
                    Socket sock;
                    try { sock = ss.accept(); } catch (Throwable t) { break; }
                    String from = sock.getInetAddress() == null ? "" : sock.getInetAddress().getHostAddress();
                    if (!peer.equals(from)) {          // not the other phone: hang up
                        try { sock.close(); } catch (Throwable ignored) {}
                        continue;
                    }
                    if (done.compareAndSet(false, true)) {
                        String got = talk(sock, payload);
                        try { sock.close(); } catch (Throwable ignored) {}
                        cb.ok(got, "the other phone");
                        return;
                    }
                    try { sock.close(); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
            }
        }, "net-listen");

        Thread dial = new Thread(() -> {
            for (int attempt = 0; attempt < 4 && !done.get(); attempt++) {
                Socket sock = null;
                try {
                    sock = new Socket();
                    sock.connect(new InetSocketAddress(InetAddress.getByName(peer), PORT),
                                 CONNECT_TIMEOUT_MS);
                    if (done.compareAndSet(false, true)) {
                        String got = talk(sock, payload);
                        try { sock.close(); } catch (Throwable ignored) {}
                        ServerSocket ss = server.get();
                        try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
                        cb.ok(got, "the other phone");
                        return;
                    }
                    try { sock.close(); } catch (Throwable ignored) {}
                    return;
                } catch (Throwable t) {
                    try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) { return; }
                }
            }
        }, "net-dial");

        listen.start();
        dial.start();

        new Thread(() -> {
            try { Thread.sleep(ACCEPT_TIMEOUT_MS + 2000); } catch (InterruptedException ignored) {}
            if (done.compareAndSet(false, true)) {
                ServerSocket ss = server.get();
                try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
                cb.fail("No answer over the tailnet. Both phones need Tailscale connected, the "
                      + "app open, and Sync tapped within about half a minute of each other.");
            }
        }, "net-timeout").start();
    }

    private static String talk(Socket sock, String payload) throws Exception {
        sock.setSoTimeout(20000);
        DataOutputStream out = new DataOutputStream(sock.getOutputStream());
        DataInputStream in = new DataInputStream(sock.getInputStream());
        byte[] mine = payload.getBytes(StandardCharsets.UTF_8);
        out.writeInt(mine.length);
        out.write(mine);
        out.flush();
        int len = in.readInt();
        if (len < 0 || len > MAX_PAYLOAD) throw new Exception("bad frame size " + len);
        byte[] theirs = new byte[len];
        in.readFully(theirs);
        return new String(theirs, StandardCharsets.UTF_8);
    }
}
