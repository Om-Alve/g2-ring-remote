package io.github.omalve.g2ringremote.launcher;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.DateFormat;
import java.util.Date;

import rikka.shizuku.Shizuku;

/**
 * Starts g2-ring-remote whenever Shizuku hands this app its binder. When Shizuku's
 * server starts (after a reboot, for example) it calls this app's ShizukuProvider,
 * which launches this process even if the app is closed, so g2-ring-remote comes back
 * without anyone opening the app. start.sh leaves an already running copy alone.
 */
public final class LauncherApp extends Application {
    static final String TAG = "G2RingRemoteLauncher";
    static final String PREFS = "launcher";
    static final String AUTO_START = "auto_start";
    /** Set by the app's Stop button so auto-start does not undo it. */
    static final String USER_STOPPED = "user_stopped";
    static final String LAST_AUTO = "last_auto";

    private final Shizuku.OnBinderReceivedListener onBinder = this::autoStart;

    @Override
    public void onCreate() {
        super.onCreate();
        Shizuku.addBinderReceivedListenerSticky(onBinder);
    }

    private void autoStart() {
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(AUTO_START, true) || prefs.getBoolean(USER_STOPPED, false)) {
            return;
        }
        if (!Shell.permitted()) {
            record(prefs, "Shizuku started, but G2 Ring Remote has no Shizuku permission yet");
            return;
        }
        new Thread(() -> record(prefs, Shell.run(Shell.START)), "auto-start").start();
    }

    static void record(SharedPreferences prefs, String result) {
        String stamped = DateFormat.getDateTimeInstance().format(new Date()) + "\n" + result;
        Log.i(TAG, "auto-start: " + result);
        prefs.edit().putString(LAST_AUTO, stamped).apply();
    }
}
