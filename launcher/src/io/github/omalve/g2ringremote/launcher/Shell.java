package io.github.omalve.g2ringremote.launcher;

import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/** Runs g2-ring-remote's on-phone scripts as the shell user through Shizuku. */
final class Shell {
    static final String DIR = "/data/local/tmp/g2-ring-remote";
    static final String START = "sh " + DIR + "/start.sh";
    static final String STOP = "sh " + DIR + "/stop.sh";
    static final String STATUS = "CLASSPATH=" + DIR + "/g2-ring-remote.dex app_process /system/bin "
            + "io.github.omalve.g2ringremote.G2RingRemote status";
    /** Reads the token from stdin, so it never appears in a process's arguments. */
    static final String SAVE_TOKEN = "mkdir -p " + DIR + " && chmod 700 " + DIR
            + " && umask 077 && cat > " + DIR + "/token && chmod 600 " + DIR + "/token"
            + " && [ -s " + DIR + "/token ] && echo 'token saved'";
    /** Same, for the Deepgram API key used by g2-ring-remote's speech-to-text proxy. */
    static final String SAVE_DEEPGRAM_KEY = "mkdir -p " + DIR + " && chmod 700 " + DIR
            + " && umask 077 && cat > " + DIR + "/deepgram-key && chmod 600 " + DIR + "/deepgram-key"
            + " && [ -s " + DIR + "/deepgram-key ] && echo 'key saved'";
    private static final long TIMEOUT_MS = 20000;

    private Shell() {}

    static boolean shizukuRunning() {
        return Shizuku.pingBinder();
    }

    static boolean permitted() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false; // the binder died between calls
        }
    }

    /** Runs {@code sh -c command} as shell; returns its combined output and exit status. */
    static String run(String command) {
        return run(command, null);
    }

    /** As {@link #run(String)}, writing {@code stdin} (if not null) to the command's input first. */
    static String run(String command, String stdin) {
        if (!permitted()) {
            return "Shizuku is not running, or G2 Ring Remote has no Shizuku permission.";
        }
        try {
            // Shizuku 13 made newProcess private in favour of UserService; the server
            // still serves it, and a one-shot script does not need a bound service.
            Method newProcess = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            final Process p = (Process) newProcess.invoke(null,
                    new String[] {"sh", "-c", command + " 2>&1"}, null, null);
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(TIMEOUT_MS);
                    p.destroy();
                } catch (InterruptedException ignored) {
                    // finished in time
                }
            });
            watchdog.setDaemon(true);
            watchdog.start();
            try (java.io.OutputStream in = p.getOutputStream()) {
                if (stdin != null) {
                    in.write(stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            String out = readAll(p.getInputStream());
            int exit = p.waitFor();
            watchdog.interrupt();
            return out.trim() + (exit == 0 ? "" : "\n(exit " + exit + ")");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return "could not run through Shizuku: " + cause;
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }
}
