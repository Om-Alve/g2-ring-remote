package io.github.omalve.g2ringremote;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Timestamped lines on stdout; the launcher redirects stdout to the log file. Never log the token. */
final class Log {
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    static volatile boolean quiet = false;

    private Log() {}

    static synchronized void i(String message) {
        if (quiet) {
            return;
        }
        System.out.println(FORMAT.format(new Date()) + "  " + message);
        System.out.flush();
    }
}
