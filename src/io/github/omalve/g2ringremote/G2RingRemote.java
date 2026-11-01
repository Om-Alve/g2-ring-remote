package io.github.omalve.g2ringremote;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * K02 ring -> Faceclaw, run as the adb shell user through app_process (no root):
 *
 * <pre>
 *   app_process /system/bin --nice-name=g2-ring-remote io.github.omalve.g2ringremote.G2RingRemote run [config]
 *   app_process /system/bin io.github.omalve.g2ringremote.G2RingRemote status|stop|simulate EVENT
 * </pre>
 *
 * The running instance owns the abstract socket {@value #SOCKET}, which also keeps
 * a second copy from starting.
 */
public final class G2RingRemote {
    static final String SOCKET = "g2-ring-remote";
    static final String DEFAULT_CONFIG = "/data/local/tmp/g2-ring-remote/g2-ring-remote.conf";

    private G2RingRemote() {}

    public static void main(String[] args) {
        String command = args.length > 0 ? args[0] : "run";
        try {
            switch (command) {
                case "run":
                    run(args.length > 1 ? args[1] : DEFAULT_CONFIG);
                    return;
                case "status":
                case "stop":
                case "simulate":
                    System.exit(control(String.join(" ", args)));
                    return;
                default:
                    System.err.println("usage: G2RingRemote run [config] | status | stop | simulate EVENT");
                    System.exit(2);
            }
        } catch (Exception e) {
            Log.i("fatal: " + e);
            System.exit(1);
        }
    }

    private static int control(String line) {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(SOCKET));
        } catch (IOException e) {
            System.out.println("g2-ring-remote is not running");
            return 3;
        }
        try {
            OutputStream out = socket.getOutputStream();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String reply;
            while ((reply = in.readLine()) != null) {
                System.out.println(reply);
            }
            return 0;
        } catch (IOException e) {
            System.out.println("control connection failed: " + e.getMessage());
            return 1;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    private static void run(String configPath) throws Exception {
        File configFile = new File(configPath);
        Config config;
        if (configFile.exists()) {
            config = Config.load(configFile);
        } else {
            config = new Config();
            Log.i("no config at " + configPath + "; using defaults");
        }

        LocalServerSocket server;
        try {
            server = new LocalServerSocket(SOCKET);
        } catch (IOException e) {
            Log.i("g2-ring-remote is already running; not starting a second copy");
            System.exit(1);
            return;
        }

        final long started = System.currentTimeMillis();
        Log.i("g2-ring-remote starting (pid " + android.os.Process.myPid() + "), matching \"" + config.matchName + "\""
                + (config.matchUniq.isEmpty() ? "" : " id " + config.matchUniq));
        for (Map.Entry<String, String> e : config.map.entrySet()) {
            Log.i("  map " + e.getKey() + " -> " + e.getValue());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> Log.i("stopping; the ring returns to Android")));

        FaceclawSender sender = new FaceclawSender(config);
        sender.start();
        Log.i("faceclaw " + config.host + ":" + config.port + " ping: " + sender.ping());

        Router router = new Router(config, sender);
        DeviceWatcher watcher = new DeviceWatcher(config, router);
        watcher.start();

        SttProxy stt = null;
        if (config.sttEnabled) {
            try {
                stt = new SttProxy(config);
                stt.start();
                Log.i("stt: " + stt.status());
            } catch (IOException e) {
                Log.i("stt: could not listen on 127.0.0.1:" + config.sttPort + ": " + e.getMessage());
                stt = null;
            }
        }

        while (true) {
            try (LocalSocket client = server.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                String line = in.readLine();
                if (line == null) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                switch (parts[0]) {
                    case "status":
                        long up = (System.currentTimeMillis() - started) / 1000;
                        out.println("running " + (up / 60) + "m" + (up % 60) + "s, pid " + android.os.Process.myPid());
                        if (watcher.grabbedNodes().isEmpty()) {
                            out.println("grabbed: none (waiting for \"" + config.matchName + "\"; is the ring connected?)");
                        }
                        for (DeviceWatcher.Node n : watcher.grabbedNodes()) {
                            out.println("grabbed: " + n.path + " \"" + n.name + "\" " + n.uniq);
                        }
                        out.println("last ring event: " + router.lastEvent());
                        out.println("faceclaw " + config.host + ":" + config.port + " ping: " + sender.ping());
                        out.println("deliveries: " + sender.stats());
                        out.println("stt: " + (stt != null ? stt.status()
                                : config.sttEnabled ? "not listening (see log)" : "off (stt.enabled=false)"));
                        break;
                    case "simulate":
                        if (parts.length != 2) {
                            out.println("usage: simulate EVENT   (e.g. touch.tap, key.PLAYPAUSE)");
                            break;
                        }
                        String gesture = router.simulate(parts[1]);
                        out.println(parts[1] + (gesture == null ? " is unmapped" : " -> " + gesture + " (queued)"));
                        break;
                    case "stop":
                        out.println("stopping; the ring returns to Android");
                        out.flush();
                        Log.i("stop requested");
                        System.exit(0);
                        break;
                    default:
                        out.println("unknown command: " + parts[0]);
                }
            } catch (IOException e) {
                Log.i("control connection error: " + e.getMessage());
            }
        }
    }
}
