package com.shashti.tv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A tiny web server so a phone on the same Wi-Fi can drive the TV: open http://TV-IP:PORT/?k=PIN in the phone's browser.
 * Only addresses of the local network are served, and every request needs the PIN.
 */
final class RemoteServer {
    interface Host {
        /** a button: up, down, left, right, ok, back, home, menu, play, back10, fwd10, skip, volup, voldown, mute */
        void cmd(String c);
        /** types text into the focused field; answers "ok" or "none" */
        void text(String s, Reply r);
        /** opens an address */
        void open(String url);
    }

    interface Reply { void done(String result); }

    private static final int FIRST_PORT = 8765;

    private final Host host;
    private final String pin;
    private ServerSocket server;
    private volatile boolean running;
    private final ArrayDeque<Long> failures = new ArrayDeque<>();

    RemoteServer(Host host, String pin) {
        this.host = host;
        this.pin = pin;
    }

    /** starts listening; returns the port */
    int start() throws IOException {
        IOException last = null;
        for (int p = FIRST_PORT; p < FIRST_PORT + 10; p++) {
            try {
                server = new ServerSocket(p);
                break;
            } catch (BindException e) {
                last = e;
            }
        }
        if (server == null) throw last != null ? last : new IOException("no free port");
        running = true;
        final ServerSocket ss = server;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    final Socket s = ss.accept();
                    new Thread(() -> handle(s), "remote-conn").start();
                } catch (IOException e) {
                    if (!running) return;
                }
            }
        }, "remote-accept");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
    }

    private static boolean localNetwork(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;   // fc00::/7 (IPv6 private)
    }

    private synchronized boolean blocked() {
        long now = System.currentTimeMillis();
        while (!failures.isEmpty() && now - failures.peekFirst() > 60000) failures.pollFirst();
        return failures.size() >= 10;
    }

    private synchronized void failed() { failures.addLast(System.currentTimeMillis()); }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(5000);
            if (!localNetwork(s.getInetAddress())) return;
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) return;
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) { /* headers are not needed */ }
            String[] parts = line.split(" ");
            if (parts.length < 2 || !"GET".equals(parts[0])) { send(s, 405, "text/plain", "no"); return; }
            String target = parts[1];
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            Map<String, String> args = new HashMap<>();
            for (String kv : query.split("&")) {
                int e = kv.indexOf('=');
                if (e > 0) args.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            }
            if (blocked()) { send(s, 429, "text/plain", "wait"); return; }
            if (!pin.equals(args.get("k"))) {
                if (!"/favicon.ico".equals(path)) failed();
                send(s, 403, "text/plain", "الرقم غلط");
                return;
            }
            switch (path) {
                case "/":
                    send(s, 200, "text/html", RemotePage.HTML.replace("@@K@@", pin));
                    break;
                case "/c":
                    if (args.get("c") != null) host.cmd(args.get("c"));
                    send(s, 200, "text/plain", "ok");
                    break;
                case "/o":
                    if (args.get("u") != null) host.open(args.get("u"));
                    send(s, 200, "text/plain", "ok");
                    break;
                case "/t": {
                    final String[] res = {"none"};
                    final CountDownLatch latch = new CountDownLatch(1);
                    host.text(args.get("s") == null ? "" : args.get("s"), r -> { res[0] = r; latch.countDown(); });
                    try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                    send(s, 200, "text/plain", res[0]);
                    break;
                }
                default:
                    send(s, 404, "text/plain", "no");
            }
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static void send(Socket s, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(head.getBytes(StandardCharsets.UTF_8));
        o.write(b);
        o.flush();
    }
}
