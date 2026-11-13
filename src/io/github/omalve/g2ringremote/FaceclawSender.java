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
import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faceclaw's local input API: one newline-terminated JSON request per TCP
 * connection, one JSON reply. Gestures and typed text go through a small bounded
 * queue on one thread, in order. Nothing is ever retried: a request that timed out
 * may already have reached the glasses, and a replay could double-tap the UI.
 */
final class FaceclawSender implements Router.GestureSink {
    static final int TIMEOUT_MS = 5000;
    /** Faceclaw's per-request limit for typed text. */
    private static final int MAX_TEXT = 64;
    private static final int QUEUE_CAPACITY = 4;
    private static final int MAX_REPLY = 64 * 1024;
    private static final Pattern OK = Pattern.compile("\"ok\"\\s*:\\s*true");
    private static final Pattern ERROR = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final Config config;
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final Object queueLock = new Object();
    private final Object statsLock = new Object();
    private int delivered;
    private int failed;
    private int dropped;
    private String lastResult = "nothing sent yet";

    /** A gesture request, or a text request whose characters may still be growing. */
    private static final class Request {
        final String gesture;
        final String source;
        final StringBuilder text;

        Request(String gesture, String source, StringBuilder text) {
            this.gesture = gesture;
            this.source = source;
            this.text = text;
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
        synchronized (queueLock) {
            if (queue.size() == QUEUE_CAPACITY) {
                countDropped(1);
                Log.i("dropped " + gesture + ": Faceclaw is not keeping up");
                return;
            }
            queue.addLast(new Request(gesture, source, null));
            queueLock.notifyAll();
        }
    }

    @Override
    public void type(String text) {
        synchronized (queueLock) {
            Request tail = queue.peekLast();
            if (tail != null && tail.text != null && tail.text.length() + text.length() <= MAX_TEXT) {
                tail.text.append(text);
                return;
            }
            if (queue.size() == QUEUE_CAPACITY) {
                countDropped(text.length());
                Log.i("dropped " + text.length() + " typed characters: Faceclaw is not keeping up");
                return;
            }
            queue.addLast(new Request(null, null, new StringBuilder(text)));
            queueLock.notifyAll();
        }
    }

    private void countDropped(int count) {
        synchronized (statsLock) {
            dropped += count;
        }
    }

    private static String textLabel(int count) {
        return count + (count == 1 ? " typed character" : " typed characters");
    }

    /** Validates the token without sending input. Returns a one-line result. */
    String ping() {
        try {
            String token = readToken();
            String result = describe(request(pingRequest(token, "input")));
            if (config.typesText()) {
                try {
                    result += ", text " + describe(request(pingRequest(token, "text")));
                } catch (IOException e) {
                    result += ", text failed: " + e.getMessage();
                }
            }
            return result;
        } catch (IOException e) {
            return "failed: " + e.getMessage();
        }
    }

    private static String pingRequest(String token, String permission) {
        return "{\"version\":1,\"token\":\"" + escape(token) + "\",\"action\":\"ping\",\"permission\":\""
                + permission + "\"}";
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
            synchronized (queueLock) {
                while (queue.isEmpty()) {
                    try {
                        queueLock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                item = queue.removeFirst();
            }
            String what = item.text != null ? textLabel(item.text.length()) : item.gesture;
            String result;
            boolean ok = false;
            try {
                String token = readToken();
                String json;
                if (item.text != null) {
                    json = "{\"version\":1,\"token\":\"" + escape(token)
                            + "\",\"action\":\"type\",\"text\":\"" + escape(item.text.toString()) + "\"}";
                } else {
                    json = "{\"version\":1,\"token\":\"" + escape(token)
                            + "\",\"action\":\"input\",\"gesture\":\"" + item.gesture
                            + "\",\"source\":\"" + item.source + "\"}";
                }
                String reply = request(json);
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
                lastResult = what + " " + result;
            }
            Log.i("faceclaw " + what + ": " + result);
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
            } else if (c == '\b') {
                out.append("\\b");
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
