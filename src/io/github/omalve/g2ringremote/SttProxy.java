package io.github.omalve.g2ringremote;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Deepgram speech-to-text behind an OpenAI-style transcription API on 127.0.0.1,
 * for Faceclaw's "Whisper server" voice provider:
 *
 * <pre>
 *   GET  /v1/models                 -> the configured Deepgram model
 *   POST /v1/audio/transcriptions   (multipart: file=WAV, model, language) -> {"text": "..."}
 * </pre>
 *
 * Faceclaw records, cuts utterances and posts WAV files; this forwards each file to
 * Deepgram's pre-recorded /v1/listen with the key from {@code stt.key-file} (read per
 * request, so rotating the key needs no restart). Transcripts are never logged.
 */
final class SttProxy {
    static final int MAX_BODY = 25 * 1024 * 1024;
    private static final int UPSTREAM_TIMEOUT_MS = 20000;

    private final Config config;
    private final Object statsLock = new Object();
    private int ok;
    private int failed;
    private String last = "no requests yet";
    private ServerSocket server;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "stt-request");
        t.setDaemon(true);
        return t;
    });

    SttProxy(Config config) {
        this.config = config;
    }

    /** Binds 127.0.0.1:stt.port and serves on a background thread. */
    void start() throws IOException {
        server = new ServerSocket(config.sttPort, 16, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    final Socket client = server.accept();
                    workers.execute(() -> handle(client));
                } catch (IOException e) {
                    if (!server.isClosed()) {
                        Log.i("stt: accept failed: " + e.getMessage());
                    }
                }
            }
        }, "stt-accept");
        accept.setDaemon(true);
        accept.start();
    }

    int port() {
        return server.getLocalPort();
    }

    String status() {
        String key = new File(config.sttKeyFile).length() > 0 ? "key saved" : "no Deepgram key yet";
        synchronized (statsLock) {
            return "listening on http://127.0.0.1:" + port() + "/v1 (Deepgram " + config.sttModel + "); "
                    + key + "; " + ok + " ok, " + failed + " failed; last: " + last;
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            Request request;
            try {
                request = Request.read(in);
            } catch (IOException e) {
                respond(out, 400, error("bad request: " + e.getMessage()));
                return;
            }
            if (request == null) {
                return;
            }
            if (!config.sttLocalKey.isEmpty()
                    && !("Bearer " + config.sttLocalKey).equals(request.headers.get("authorization"))) {
                respond(out, 401, error("missing or wrong local API key"));
                return;
            }
            String path = request.path.replaceFirst("\\?.*$", "");
            if (request.method.equals("GET") && (path.equals("/v1/models") || path.equals("/models"))) {
                respond(out, 200, "{\"object\":\"list\",\"data\":[{\"id\":\"" + json(config.sttModel)
                        + "\",\"object\":\"model\",\"owned_by\":\"deepgram\"}]}");
            } else if (request.method.equals("POST")
                    && (path.equals("/v1/audio/transcriptions") || path.equals("/audio/transcriptions"))) {
                transcribe(request, out);
            } else {
                respond(out, 404, error("not found: " + request.method + " " + path));
            }
        } catch (IOException e) {
            // client went away
        }
    }

    private void transcribe(Request request, OutputStream out) throws IOException {
        long started = System.currentTimeMillis();
        Map<String, Part> parts;
        try {
            parts = Multipart.parse(request.headers.get("content-type"), request.body);
        } catch (IllegalArgumentException e) {
            fail(out, 400, "bad multipart body: " + e.getMessage());
            return;
        }
        Part file = parts.get("file");
        if (file == null || file.data.length == 0) {
            fail(out, 400, "no audio file in the request");
            return;
        }
        String key = readKey();
        if (key.isEmpty()) {
            fail(out, 503, "no Deepgram API key saved (" + config.sttKeyFile + ")");
            return;
        }
        String model = text(parts.get("model"));
        if (model.isEmpty()) {
            model = config.sttModel;
        }
        String language = text(parts.get("language"));
        if (language.isEmpty()) {
            language = config.sttLanguage;
        }
        String query = "?model=" + enc(model) + "&smart_format=true"
                + (language.isEmpty() ? "" : "&language=" + enc(language));
        String contentType = file.contentType.isEmpty() ? "audio/wav" : file.contentType;
        try {
            String transcript = deepgram(config.sttEndpoint + query, key, contentType, file.data);
            long ms = System.currentTimeMillis() - started;
            synchronized (statsLock) {
                ok++;
                last = "ok, " + (file.data.length / 1024) + " KB audio in " + ms + " ms";
            }
            respond(out, 200, "{\"text\":\"" + json(transcript) + "\"}");
        } catch (IOException e) {
            fail(out, 502, "Deepgram: " + e.getMessage());
        }
    }

    private void fail(OutputStream out, int status, String message) throws IOException {
        synchronized (statsLock) {
            failed++;
            last = message;
        }
        Log.i("stt: " + message);
        respond(out, status, error(message));
    }

    private String readKey() {
        try {
            return new String(Files.readAllBytes(new File(config.sttKeyFile).toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static String deepgram(String url, String key, String contentType, byte[] audio) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(UPSTREAM_TIMEOUT_MS);
            c.setReadTimeout(UPSTREAM_TIMEOUT_MS);
            c.setDoOutput(true);
            // No fixed-length streaming mode: in that mode a 401 surfaces as an opaque
            // HttpRetryException instead of Deepgram's "Invalid credentials" body.
            c.setRequestProperty("Authorization", "Token " + key);
            c.setRequestProperty("Content-Type", contentType);
            try (OutputStream o = c.getOutputStream()) {
                o.write(audio);
            }
            int status = c.getResponseCode();
            InputStream body = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = body == null ? "" : new String(readAll(body, 4 * 1024 * 1024), StandardCharsets.UTF_8);
            if (status != 200) {
                String reason = jsonString(text, "err_msg");
                throw new IOException("HTTP " + status + (reason != null ? ": " + reason
                        : text.isEmpty() ? "" : ": " + text.substring(0, Math.min(200, text.length()))));
            }
            // The first "transcript" is results.channels[0].alternatives[0].transcript.
            String transcript = jsonString(text, "transcript");
            if (transcript == null) {
                throw new IOException("response had no transcript");
            }
            return transcript;
        } finally {
            c.disconnect();
        }
    }

    // --- HTTP ---------------------------------------------------------------------

    static final class Request {
        String method;
        String path;
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        /** Reads one request; null if the client closed before sending anything. */
        static Request read(InputStream in) throws IOException {
            String line = readLine(in);
            if (line == null) {
                return null;
            }
            String[] first = line.split(" ");
            if (first.length < 2) {
                throw new IOException("malformed request line");
            }
            Request r = new Request();
            r.method = first[0].toUpperCase(Locale.US);
            r.path = first[1];
            String header;
            while ((header = readLine(in)) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0) {
                    r.headers.put(header.substring(0, colon).trim().toLowerCase(Locale.US),
                            header.substring(colon + 1).trim());
                }
            }
            String length = r.headers.get("content-length");
            if ("chunked".equalsIgnoreCase(r.headers.get("transfer-encoding"))) {
                r.body = readChunked(in);
            } else if (length != null) {
                int n = Integer.parseInt(length.trim());
                if (n < 0 || n > MAX_BODY) {
                    throw new IOException("body too large");
                }
                r.body = readExactly(in, n);
            }
            return r;
        }

        private static byte[] readChunked(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = readLine(in);
                if (sizeLine == null) {
                    throw new IOException("truncated chunked body");
                }
                int size = Integer.parseInt(sizeLine.split(";")[0].trim(), 16);
                if (size == 0) {
                    while (true) { // trailers
                        String t = readLine(in);
                        if (t == null || t.isEmpty()) {
                            break;
                        }
                    }
                    return out.toByteArray();
                }
                if (out.size() + size > MAX_BODY) {
                    throw new IOException("body too large");
                }
                out.write(readExactly(in, size));
                readLine(in);
            }
        }
    }

    private static void respond(OutputStream out, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request" : status == 401 ? "Unauthorized"
                : status == 404 ? "Not Found" : status == 503 ? "Service Unavailable" : "Bad Gateway";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String error(String message) {
        return "{\"error\":{\"message\":\"" + json(message) + "\"}}";
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int n = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, n, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
            if (line.size() > 16 * 1024) {
                throw new IOException("header line too long");
            }
        }
        return line.size() == 0 ? null : line.toString("ISO-8859-1");
    }

    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new IOException("truncated body");
            }
            off += r;
        }
        return buf;
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < max) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // --- multipart ----------------------------------------------------------------

    static final class Part {
        String contentType = "";
        byte[] data = new byte[0];
    }

    static final class Multipart {
        static Map<String, Part> parse(String contentType, byte[] body) {
            if (contentType == null || !contentType.toLowerCase(Locale.US).startsWith("multipart/form-data")) {
                throw new IllegalArgumentException("expected multipart/form-data");
            }
            String boundary = null;
            for (String piece : contentType.split(";")) {
                String p = piece.trim();
                if (p.toLowerCase(Locale.US).startsWith("boundary=")) {
                    boundary = p.substring(9).replaceAll("^\"|\"$", "");
                }
            }
            if (boundary == null || boundary.isEmpty()) {
                throw new IllegalArgumentException("no boundary");
            }
            byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            Map<String, Part> parts = new HashMap<>();
            int pos = indexOf(body, delimiter, 0);
            if (pos < 0) {
                throw new IllegalArgumentException("boundary not found in body");
            }
            while (true) {
                pos += delimiter.length;
                if (pos + 1 < body.length && body[pos] == '-' && body[pos + 1] == '-') {
                    break; // closing delimiter
                }
                pos = skipCrlf(body, pos);
                int headersEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), pos);
                if (headersEnd < 0) {
                    throw new IllegalArgumentException("part without headers");
                }
                String headers = new String(body, pos, headersEnd - pos, StandardCharsets.ISO_8859_1);
                int dataStart = headersEnd + 4;
                int next = indexOf(body, delimiter, dataStart);
                if (next < 0) {
                    throw new IllegalArgumentException("unterminated part");
                }
                int dataEnd = next;
                if (dataEnd >= 2 && body[dataEnd - 2] == '\r' && body[dataEnd - 1] == '\n') {
                    dataEnd -= 2;
                }
                String name = null;
                Part part = new Part();
                for (String h : headers.split("\r\n")) {
                    String lower = h.toLowerCase(Locale.US);
                    if (lower.startsWith("content-disposition:")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("(?i)\\bname=\"([^\"]*)\"").matcher(h);
                        if (m.find()) {
                            name = m.group(1);
                        }
                    } else if (lower.startsWith("content-type:")) {
                        part.contentType = h.substring(13).trim();
                    }
                }
                part.data = java.util.Arrays.copyOfRange(body, dataStart, dataEnd);
                if (name != null) {
                    parts.put(name, part);
                }
                pos = next;
            }
            return parts;
        }

        private static int skipCrlf(byte[] b, int pos) {
            if (pos + 1 < b.length && b[pos] == '\r' && b[pos + 1] == '\n') {
                return pos + 2;
            }
            return pos;
        }

        static int indexOf(byte[] haystack, byte[] needle, int from) {
            outer:
            for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (haystack[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }

    // --- tiny JSON helpers ----------------------------------------------------------

    private static String text(Part part) {
        return part == null ? "" : new String(part.data, StandardCharsets.UTF_8).trim();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    /** Escapes a string for a JSON string literal. */
    static String json(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /** The string value of the first {@code "key": "..."} in a JSON text, unescaped; null if absent. */
    static String jsonString(String text, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"").matcher(text);
        if (!m.find()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = m.end(); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char e = text.charAt(++i);
            switch (e) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'u':
                    if (i + 4 < text.length()) {
                        out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    break;
                default: out.append(e); // \" \\ \/
            }
        }
        return null;
    }
}
