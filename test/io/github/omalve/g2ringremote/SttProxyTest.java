package io.github.omalve.g2ringremote;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** JVM checks for the Deepgram proxy against a fake Deepgram. Run by test.sh. */
public final class SttProxyTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        Log.quiet = true;
        multipartParsesFaceclawStyleBody();
        jsonHelpers();
        endToEndAgainstFakeDeepgram();
        System.out.println("stt-proxy: " + passed + " checks passed");
    }

    private static final String BOUNDARY = "----faceclaw1234";

    static byte[] multipart(String language, byte[] wav) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        String[][] fields = {{"model", "nova-3"}, {"response_format", "json"}, {"temperature", "0"},
            {"language", language}};
        for (String[] f : fields) {
            b.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + f[0] + "\"\r\n\r\n"
                    + f[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        b.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        b.write(wav);
        b.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return b.toByteArray();
    }

    private static byte[] fakeWav() {
        byte[] wav = new byte[3000];
        for (int i = 0; i < wav.length; i++) {
            wav[i] = (byte) (i * 7); // includes \r, \n and '-' bytes inside the audio
        }
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, wav, 0, 4);
        return wav;
    }

    private static void multipartParsesFaceclawStyleBody() throws Exception {
        byte[] wav = fakeWav();
        Map<String, SttProxy.Part> parts = SttProxy.Multipart.parse(
                "multipart/form-data; boundary=" + BOUNDARY, multipart("de", wav));
        equal("nova-3", new String(parts.get("model").data, StandardCharsets.UTF_8), "model field");
        equal("de", new String(parts.get("language").data, StandardCharsets.UTF_8), "language field");
        equal("audio/wav", parts.get("file").contentType, "file content type");
        check(java.util.Arrays.equals(wav, parts.get("file").data), "file bytes survive intact");
        try {
            SttProxy.Multipart.parse("application/json", new byte[0]);
            throw new AssertionError("accepted non-multipart");
        } catch (IllegalArgumentException expected) {
            passed++;
        }
    }

    private static void jsonHelpers() {
        String deepgram = "{\"metadata\":{},\"results\":{\"channels\":[{\"alternatives\":[{\"transcript\":"
                + "\"He said \\\"hi\\\" \\u00e9t\\u00e9\\nok\",\"confidence\":0.9,\"paragraphs\":{\"transcript\":\"x\"}}]}]}}";
        equal("He said \"hi\" été\nok", SttProxy.jsonString(deepgram, "transcript"), "first transcript, unescaped");
        equal(null, SttProxy.jsonString("{}", "transcript"), "absent key");
        equal("a\\\"b\\nc", SttProxy.json("a\"b\nc"), "escape for JSON");
    }

    private static void endToEndAgainstFakeDeepgram() throws Exception {
        final AtomicReference<String> seenQuery = new AtomicReference<>();
        final AtomicReference<String> seenAuth = new AtomicReference<>();
        final AtomicReference<String> seenType = new AtomicReference<>();
        final AtomicReference<byte[]> seenBody = new AtomicReference<>();
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/v1/listen", ex -> {
            seenQuery.set(ex.getRequestURI().getQuery());
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            seenType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            seenBody.set(readAll(ex.getRequestBody()));
            boolean good = "Token dg-key-123".equals(seenAuth.get());
            byte[] reply = (good
                    ? "{\"results\":{\"channels\":[{\"alternatives\":[{\"transcript\":\"Open the \\\"music\\\" player.\"}]}]}}"
                    : "{\"err_code\":\"INVALID_AUTH\",\"err_msg\":\"Invalid credentials.\"}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(good ? 200 : 401, reply.length);
            ex.getResponseBody().write(reply);
            ex.close();
        });
        fake.start();
        File key = File.createTempFile("deepgram-key", ".txt");
        key.deleteOnExit();
        try {
            Config c = new Config();
            c.sttPort = freePort();
            c.sttKeyFile = key.getPath();
            c.sttEndpoint = "http://127.0.0.1:" + fake.getAddress().getPort() + "/v1/listen";
            SttProxy proxy = new SttProxy(c);
            proxy.start();
            String base = "http://127.0.0.1:" + proxy.port();

            Object[] models = http("GET", base + "/v1/models", null, null);
            equal(200, models[0], "models status");
            check(((String) models[1]).contains("\"id\":\"nova-3\""), "models lists the Deepgram model");

            byte[] wav = fakeWav();
            Object[] noKey = http("POST", base + "/v1/audio/transcriptions", multipart("", wav), null);
            equal(503, noKey[0], "no key saved -> 503");
            check(((String) noKey[1]).contains("no Deepgram API key"), "explains the missing key");

            Files.write(key.toPath(), "dg-key-123\n".getBytes(StandardCharsets.UTF_8));
            Object[] good = http("POST", base + "/v1/audio/transcriptions", multipart("", wav), null);
            equal(200, good[0], "transcription status");
            equal("{\"text\":\"Open the \\\"music\\\" player.\"}", good[1], "OpenAI-style reply");
            equal("Token dg-key-123", seenAuth.get(), "Deepgram auth header");
            equal("audio/wav", seenType.get(), "audio content type");
            check(java.util.Arrays.equals(wav, seenBody.get()), "audio forwarded byte for byte");
            equal("model=nova-3&smart_format=true&language=en", seenQuery.get(), "default language en");

            http("POST", base + "/v1/audio/transcriptions", multipart("de", wav), null);
            equal("model=nova-3&smart_format=true&language=de", seenQuery.get(), "Faceclaw's language wins");

            Files.write(key.toPath(), "wrong".getBytes(StandardCharsets.UTF_8));
            Object[] rejected = http("POST", base + "/v1/audio/transcriptions", multipart("", wav), null);
            equal(502, rejected[0], "rejected key -> 502");
            check(((String) rejected[1]).contains("Invalid credentials."), "passes Deepgram's reason on");
            check(proxy.status().contains("2 ok, 2 failed"), "status counts: " + proxy.status());
            check(!proxy.status().contains("music"), "status never contains transcript text");

            Config locked = new Config();
            locked.sttPort = freePort();
            locked.sttKeyFile = key.getPath();
            locked.sttLocalKey = "local-secret";
            SttProxy guarded = new SttProxy(locked);
            guarded.start();
            String gb = "http://127.0.0.1:" + guarded.port();
            equal(401, http("GET", gb + "/v1/models", null, null)[0], "local key required");
            equal(200, http("GET", gb + "/v1/models", null, "Bearer local-secret")[0], "local key accepted");
            equal(404, http("GET", base + "/nope", null, null)[0], "unknown path");
        } finally {
            fake.stop(0);
        }
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Object[] http(String method, String url, byte[] body, String auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        if (auth != null) {
            c.setRequestProperty("Authorization", auth);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body);
            }
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
        c.disconnect();
        return new Object[] {status, text};
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("failed: " + what);
        }
        passed++;
    }

    private static void equal(Object expected, Object actual, String what) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            throw new AssertionError(what + ": expected <" + expected + "> but got <" + actual + ">");
        }
        passed++;
    }
}
