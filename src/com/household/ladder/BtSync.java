package com.household.ladder;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import android.util.Log;

/**
 * Phone-to-phone sync over Bluetooth Classic (RFCOMM). No network, no pairing
 * server, nothing off the two devices.
 *
 * Both phones tap Sync. Each one simultaneously listens on a fixed service UUID
 * and tries to dial the other, so whoever gets there first wins the race and the
 * loser's attempt is cancelled. That way neither person has to be "the host".
 *
 * The exchange is the whole event log in both directions. The log is small
 * (kilobytes) and the merge on the JS side is idempotent and order-independent,
 * so a full swap is simpler and safer than negotiating version vectors.
 *
 * Framing: 4-byte big-endian length, then UTF-8 JSON.
 */
public class BtSync {

    public static final UUID SERVICE =
        UUID.fromString("8f1d2a60-5b3e-4c77-9a21-6e0c7d4f1b88");
    private static final String NAME = "ChoreLadderSync";
    private static final int ACCEPT_TIMEOUT_MS = 150000;   // 2.5 min: people are not synchronised
    private static final String TAG = "HHBt";
    private static final int MAX_PAYLOAD = 8 * 1024 * 1024;

    public interface Result {
        void ok(String peerPayload, String peerName);
        void fail(String reason);
    }

    /** Runs off the UI thread. Caller marshals the callback back. */
    /**
     * role decides who opens the connection, which is the whole point: if both
     * phones listen AND dial at once, two sockets can form, each side can win a
     * different one, and then both sit writing into a socket the other has
     * already closed. The organizer's phone listens, the member's dials. No race, exactly one socket.
     * After 25 seconds with nothing, each side also tries the other half, so a
     * misconfigured pair still connects rather than failing forever.
     */
    public static void exchange(final BluetoothAdapter adapter,
                                final String peerMac,
                                final String payload,
                                final String role,
                                final Result cb) {
        if (adapter == null) { cb.fail("This phone has no Bluetooth."); return; }
        if (!adapter.isEnabled()) { cb.fail("Bluetooth is off. Turn it on and tap Sync again."); return; }

        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicReference<BluetoothServerSocket> server = new AtomicReference<>();

        // --- listener ---
        Thread listen = new Thread(() -> {
            BluetoothServerSocket ss = null;
            try {
                try {
                    ss = adapter.listenUsingRfcommWithServiceRecord(NAME, SERVICE);
                    Log.i(TAG, "listening (secure)");
                } catch (Throwable secureFailed) {
                    // Some builds refuse a secure service record for an app UUID.
                    ss = adapter.listenUsingInsecureRfcommWithServiceRecord(NAME, SERVICE);
                    Log.i(TAG, "listening (insecure fallback)");
                }
                server.set(ss);
                BluetoothSocket sock = ss.accept(ACCEPT_TIMEOUT_MS);
                if (sock != null && done.compareAndSet(false, true)) {
                    Log.i(TAG, "accepted a connection from " + safeName(sock.getRemoteDevice()));
                    try { adapter.cancelDiscovery(); } catch (Throwable ignored) {}
                    String peer = talk(sock, payload);
                    String nm = safeName(sock.getRemoteDevice());
                    closeQuietly(sock);
                    cb.ok(peer, nm);
                } else {
                    closeQuietly(sock);
                }
            } catch (Throwable t) {
                Log.w(TAG, "listen ended: " + t);
            } finally {
                try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
            }
        }, "bt-listen");

        // --- dialer ---
        Thread dial = new Thread(() -> {
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}  // let listeners settle
            BluetoothDevice target = null;
            try {
                if (peerMac != null && !peerMac.isEmpty()) {
                    target = adapter.getRemoteDevice(peerMac);
                } else {
                    Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                    if (bonded != null && bonded.size() == 1) target = bonded.iterator().next();
                }
            } catch (Throwable t) {
                if (done.compareAndSet(false, true)) cb.fail("That paired phone is not recognised.");
                return;
            }
            Log.i(TAG, "dialling " + (target == null ? "nobody" : safeName(target)));
            if (target == null) {
                // No target chosen and more than one paired device: the listener
                // can still be the one that works, so do not fail yet.
                return;
            }
            // A couple of attempts: the other phone may not be listening yet.
            for (int attempt = 0; attempt < 20 && !done.get(); attempt++) {
                BluetoothSocket sock = null;
                try {
                    try { adapter.cancelDiscovery(); } catch (Throwable ignored) {}
                    try {
                        sock = target.createRfcommSocketToServiceRecord(SERVICE);
                        sock.connect();
                        Log.i(TAG, "dialled (secure) on attempt " + (attempt + 1));
                    } catch (Throwable secureFailed) {
                        closeQuietly(sock);
                        Log.i(TAG, "secure dial failed (" + secureFailed.getMessage()
                                 + "), trying insecure");
                        sock = target.createInsecureRfcommSocketToServiceRecord(SERVICE);
                        sock.connect();
                        Log.i(TAG, "dialled (insecure) on attempt " + (attempt + 1));
                    }
                    if (done.compareAndSet(false, true)) {
                        String peer = talk(sock, payload);
                        String nm = safeName(target);
                        closeQuietly(sock);
                        BluetoothServerSocket ss = server.get();
                        try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
                        cb.ok(peer, nm);
                        return;
                    } else {
                        closeQuietly(sock);
                        return;
                    }
                } catch (Throwable t) {
                    closeQuietly(sock);
                    Log.i(TAG, "dial attempt " + (attempt + 1) + " failed: " + t.getMessage());
                    try { Thread.sleep(4000); } catch (InterruptedException ignored) { return; }
                }
            }
        }, "bt-dial");

        final boolean listensFirst = !"M".equals(role);   // 'A' listens, 'M' dials
        if (listensFirst) {
            listen.start();
            new Thread(() -> {                      // safety net if the member never dials
                try { Thread.sleep(25000); } catch (InterruptedException ignored) { return; }
                if (!done.get()) { Log.i(TAG, "no inbound call, dialling as well"); dial.start(); }
            }, "bt-late-dial").start();
        } else {
            dial.start();
            new Thread(() -> {                      // safety net if the organizer never listens
                try { Thread.sleep(25000); } catch (InterruptedException ignored) { return; }
                if (!done.get()) { Log.i(TAG, "no answer, listening as well"); listen.start(); }
            }, "bt-late-listen").start();
        }
        Log.i(TAG, "role=" + role + " -> " + (listensFirst ? "listening first" : "dialling first"));

        // --- give up together ---
        new Thread(() -> {
            try { Thread.sleep(ACCEPT_TIMEOUT_MS + 2000); } catch (InterruptedException ignored) {}
            if (done.compareAndSet(false, true)) {
                BluetoothServerSocket ss = server.get();
                try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
                cb.fail("No answer from the other phone after two and a half minutes. Check that it has Bluetooth on, the app open, the Nearby devices permission allowed, and that Sync was tapped there too.");
            }
        }, "bt-timeout").start();
    }

    /** Send ours, read theirs. Both sides do exactly this, so order does not matter. */
    private static String talk(final BluetoothSocket sock, String payload) throws Exception {
        final AtomicBoolean finished = new AtomicBoolean(false);
        new Thread(() -> {
            try { Thread.sleep(25000); } catch (InterruptedException ignored) { return; }
            if (!finished.get()) {
                Log.w(TAG, "handshake stalled, closing the socket to unblock the read");
                closeQuietly(sock);
            }
        }, "bt-handshake-watchdog").start();
        OutputStream os = sock.getOutputStream();
        InputStream is = sock.getInputStream();
        DataOutputStream out = new DataOutputStream(os);
        DataInputStream in = new DataInputStream(is);

        byte[] mine = payload.getBytes(StandardCharsets.UTF_8);
        Log.i(TAG, "sending " + mine.length + " bytes");
        out.writeInt(mine.length);
        out.write(mine);
        out.flush();

        int len = in.readInt();
        Log.i(TAG, "receiving " + len + " bytes");
        if (len < 0 || len > MAX_PAYLOAD) throw new Exception("bad frame size " + len);
        byte[] theirs = new byte[len];
        in.readFully(theirs);
        finished.set(true);
        return new String(theirs, StandardCharsets.UTF_8);
    }

    private static String safeName(BluetoothDevice d) {
        try { String n = d.getName(); return (n == null || n.isEmpty()) ? d.getAddress() : n; }
        catch (Throwable t) { return "the other phone"; }
    }
    private static void closeQuietly(BluetoothSocket s) {
        try { if (s != null) s.close(); } catch (Throwable ignored) {}
    }
}
