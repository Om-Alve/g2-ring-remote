package io.github.omalve.g2ringremote;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faceclaw's local input API: one newline-terminated JSON request per TCP
 * connection, one JSON reply. Gestures go through a small bounded queue on one
 * thread, in order. Nothing is ever retried: a request that timed out may
 * already have reached the glasses, and a replay could double-tap the UI.
 */
final class FaceclawSender implements Router.GestureSink {
    static final int TIMEOUT_MS = 5000;
    private static final int MAX_REPLY = 64 * 1024;
    private static final Pattern OK = Pattern.compile("\"ok\"\\s*:\\s*true");
    private static final Pattern ERROR = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final Config config;
    private final BlockingQueue<Request> queue = new ArrayBlockingQueue<>(4);
    private final Object statsLock = new Object();
    private int delivered;
    private int failed;
    private int dropped;
    private String lastResult = "nothing sent yet";

    private static final class Request {
        final String gesture;
        final String source;

        Request(String gesture, String source) {
            this.gesture = gesture;
            this.source = source;
        }
    }

    FaceclawSender(Config config) {
        this.config = config;
    }

    void start() {
        Thread worker = new Thread(this::drain, "faceclaw-sender");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void deliver(String event, String gesture, String source) {
        if (!queue.offer(new Request(gesture, source))) {
            synchronized (statsLock) {
                dropped++;
            }
            Log.i("dropped " + gesture + ": Faceclaw is not keeping up");
        }
    }

    /** Validates the token without sending input. Returns a one-line result. */
    String ping() {
        try {
            String token = readToken();
            String reply = request("{\"version\":1,\"token\":\"" + escape(token)
                    + "\",\"action\":\"ping\",\"permission\":\"input\"}");
            return describe(reply);
        } catch (IOException e) {
            return "failed: " + e.getMessage();
        }
    }

    String stats() {
        synchronized (statsLock) {
            return "delivered " + delivered + ", failed " + failed + ", dropped " + dropped
                    + "; last: " + lastResult;
        }
    }

    private void drain() {
        while (true) {
            Request item;
            try {
                item = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            String result;
            boolean ok = false;
            try {
                String token = readToken();
                String reply = request("{\"version\":1,\"token\":\"" + escape(token)
                        + "\",\"action\":\"input\",\"gesture\":\"" + item.gesture
                        + "\",\"source\":\"" + item.source + "\"}");
                ok = OK.matcher(reply).find();
                result = describe(reply);
            } catch (IOException e) {
                result = "failed: " + e.getMessage();
            }
            synchronized (statsLock) {
                if (ok) {
                    delivered++;
                } else {
                    failed++;
                }
                lastResult = item.gesture + " " + result;
            }
            Log.i("faceclaw " + item.gesture + ": " + result);
        }
    }

    private String readToken() throws IOException {
        File file = new File(config.tokenFile);
        if (!file.exists()) {
            throw new IOException("no token file at " + config.tokenFile);
        }
        String token = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        if (token.isEmpty()) {
            throw new IOException("token file " + config.tokenFile + " is empty");
        }
        return token;
    }

    private String request(String json) throws IOException {
        byte[] line = (json + "\n").getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.host, config.port), TIMEOUT_MS);
            // Faceclaw's own read and dispatch deadlines are 5 s each.
            socket.setSoTimeout(2 * TIMEOUT_MS + 1000);
            OutputStream out = socket.getOutputStream();
            out.write(line);
            out.flush();
            String reply = readLine(socket.getInputStream());
            if (reply == null) {
                throw new IOException("Faceclaw closed the connection without a reply");
            }
            return reply;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return buffer.toString("UTF-8");
            }
            buffer.write(b);
            if (buffer.size() > MAX_REPLY) {
                throw new IOException("Faceclaw reply exceeded 64 KiB");
            }
        }
        return buffer.size() == 0 ? null : buffer.toString("UTF-8");
    }

    static String describe(String reply) {
        if (OK.matcher(reply).find()) {
            return "ok";
        }
        Matcher error = ERROR.matcher(reply);
        Matcher message = MESSAGE.matcher(reply);
        String code = error.find() ? error.group(1) : "unknown";
        return code + (message.find() ? ": " + message.group(1) : "");
    }

    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
