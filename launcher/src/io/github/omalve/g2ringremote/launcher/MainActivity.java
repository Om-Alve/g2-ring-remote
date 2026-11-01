package io.github.omalve.g2ringremote.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * One screen: Shizuku state, the Faceclaw input token, start/stop/status for
 * g2-ring-remote, and the auto-start switch.
 */
public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 1;
    private static final Pattern PING = Pattern.compile("faceclaw \\S+ ping: (.*)");
    private static final Pattern LAST_DELIVERY = Pattern.compile("deliveries: .*?; last: (.*)");
    private static final Pattern STT = Pattern.compile("(?m)^stt: (.*)$");
    private static final String STT_URL = "http://127.0.0.1:8004/v1";

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView state;
    private TextView tokenState;
    private EditText tokenField;
    private TextView sttState;
    private EditText deepgramField;
    private Button saveDeepgram;
    private TextView output;
    private Button grant;
    private Button paste;
    private Button saveToken;
    private Button start;
    private Button stop;
    private Button refresh;

    private final Shizuku.OnBinderReceivedListener onBinder = this::showState;
    private final Shizuku.OnBinderDeadListener onDead = this::showState;
    private final Shizuku.OnRequestPermissionResultListener onPermission = (code, result) -> {
        showState();
        if (result == PackageManager.PERMISSION_GRANTED) {
            runAsync("Starting g2-ring-remote…", Shell.START);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences(LauncherApp.PREFS, MODE_PRIVATE);

        int pad = dp(16);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("G2 Ring Remote");
        title.setTextSize(24);
        column.addView(title);

        TextView about = new TextView(this);
        about.setText("Keeps the K02 ring connected to Faceclaw instead of the phone. "
                + "Runs g2-ring-remote through Shizuku, and starts it again whenever Shizuku starts.");
        about.setPadding(0, dp(4), 0, dp(12));
        column.addView(about);

        state = new TextView(this);
        state.setTextSize(16);
        column.addView(state);
        grant = button(column, "Grant Shizuku permission", v -> Shizuku.requestPermission(PERMISSION_REQUEST));

        column.addView(heading("Faceclaw input token"));
        TextView tokenHelp = new TextView(this);
        tokenHelp.setText("In Faceclaw: Settings → API Keys → Input tokens → Create token, with only "
                + "\"Provide input\". Choose \"Copy token to phone clipboard\", then paste it here.");
        column.addView(tokenHelp);
        tokenState = new TextView(this);
        tokenState.setTextSize(16);
        tokenState.setPadding(0, dp(8), 0, dp(4));
        column.addView(tokenState);
        tokenField = new EditText(this);
        tokenField.setHint("fc1_…");
        tokenField.setSingleLine(true);
        tokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        column.addView(tokenField);
        LinearLayout tokenButtons = new LinearLayout(this);
        tokenButtons.setOrientation(LinearLayout.HORIZONTAL);
        paste = button(tokenButtons, "Paste", v -> pasteToken());
        saveToken = button(tokenButtons, "Save token", v -> saveToken());
        for (int i = 0; i < tokenButtons.getChildCount(); i++) {
            tokenButtons.getChildAt(i).setLayoutParams(
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        column.addView(tokenButtons);

        column.addView(heading("Deepgram voice"));
        TextView sttHelp = new TextView(this);
        sttHelp.setText("g2-ring-remote also runs a speech-to-text server on this phone that sends Faceclaw's "
                + "voice recordings to Deepgram. Save your Deepgram API key here, then in Faceclaw set "
                + "the voice provider to \"Whisper server\" with URL " + STT_URL + " (leave the model "
                + "empty or set nova-3).");
        column.addView(sttHelp);
        sttState = new TextView(this);
        sttState.setTextSize(16);
        sttState.setPadding(0, dp(8), 0, dp(4));
        column.addView(sttState);
        deepgramField = new EditText(this);
        deepgramField.setHint("Deepgram API key");
        deepgramField.setSingleLine(true);
        deepgramField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        column.addView(deepgramField);
        LinearLayout sttButtons = new LinearLayout(this);
        sttButtons.setOrientation(LinearLayout.HORIZONTAL);
        button(sttButtons, "Paste", v -> pasteInto(deepgramField, sttState));
        saveDeepgram = button(sttButtons, "Save key", v -> saveDeepgramKey());
        for (int i = 0; i < sttButtons.getChildCount(); i++) {
            sttButtons.getChildAt(i).setLayoutParams(
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        column.addView(sttButtons);

        column.addView(heading("g2-ring-remote"));
        start = button(column, "Start g2-ring-remote", v -> {
            prefs.edit().putBoolean(LauncherApp.USER_STOPPED, false).apply();
            runAsync("Starting g2-ring-remote…", Shell.START);
        });
        stop = button(column, "Stop g2-ring-remote (ring goes back to the phone)", v -> {
            prefs.edit().putBoolean(LauncherApp.USER_STOPPED, true).apply();
            runAsync("Stopping g2-ring-remote…", Shell.STOP);
        });
        refresh = button(column, "Refresh status", v -> runAsync("Checking…", Shell.STATUS));

        Switch auto = new Switch(this);
        auto.setText("Start g2-ring-remote whenever Shizuku starts");
        auto.setChecked(prefs.getBoolean(LauncherApp.AUTO_START, true));
        auto.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(LauncherApp.AUTO_START, on).apply());
        auto.setPadding(0, dp(12), 0, dp(12));
        column.addView(auto);

        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        column.addView(output);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(column);
        setContentView(scroll);

        Shizuku.addBinderReceivedListenerSticky(onBinder);
        Shizuku.addBinderDeadListener(onDead);
        Shizuku.addRequestPermissionResultListener(onPermission);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showState();
        if (Shell.permitted()) {
            runAsync("Checking…", Shell.STATUS);
        } else {
            tokenState.setText("Token: needs Shizuku to check or save.");
            String last = prefs.getString(LauncherApp.LAST_AUTO, null);
            output.setText(last == null ? "" : "Last automatic start: " + last);
        }
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(onBinder);
        Shizuku.removeBinderDeadListener(onDead);
        Shizuku.removeRequestPermissionResultListener(onPermission);
        super.onDestroy();
    }

    private void pasteToken() {
        pasteInto(tokenField, tokenState);
    }

    private void pasteInto(EditText field, TextView state) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) {
            state.setText("The clipboard is empty. Copy the key first.");
            return;
        }
        field.setText(clip.getItemAt(0).getText().toString().trim());
    }

    private void saveDeepgramKey() {
        final String key = deepgramField.getText().toString().trim();
        if (key.isEmpty() || key.contains(" ") || key.contains("\n")) {
            sttState.setText(key.isEmpty() ? "Paste a key first." : "That doesn't look like a key: it contains spaces.");
            return;
        }
        sttState.setText("Saving…");
        new Thread(() -> {
            String saved = Shell.run(Shell.SAVE_DEEPGRAM_KEY, key + "\n");
            boolean ok = saved.endsWith("key saved");
            String status = ok ? Shell.run(Shell.STATUS) : "";
            main.post(() -> {
                if (!ok) {
                    sttState.setText("Could not save the key.");
                    output.setText(saved);
                    return;
                }
                deepgramField.setText(""); // the helper's file is the only copy
                output.setText(status);
                showTokenState(status);
            });
        }, "save-deepgram").start();
    }

    /** Reads the stt line from g2-ring-remote's status output. */
    private void showSttState(String status) {
        Matcher m = STT.matcher(status);
        if (!m.find()) {
            return;
        }
        String line = m.group(1);
        if (line.contains("no Deepgram key yet")) {
            sttState.setText("Deepgram: no key saved yet.");
        } else if (line.startsWith("listening")) {
            String last = line.replaceFirst(".*last: ", "");
            sttState.setText("Deepgram: key saved; server at " + STT_URL + ".\nLast request: " + last);
        } else {
            sttState.setText("Deepgram: " + line);
        }
    }

    private void saveToken() {
        final String token = tokenField.getText().toString().trim();
        if (token.isEmpty()) {
            tokenState.setText("Paste a token first.");
            return;
        }
        if (token.contains(" ") || token.contains("\n")) {
            tokenState.setText("That doesn't look like a token: it contains spaces.");
            return;
        }
        tokenState.setText("Saving…");
        output.setText("");
        new Thread(() -> {
            String saved = Shell.run(Shell.SAVE_TOKEN, token + "\n");
            boolean ok = saved.endsWith("token saved");
            String status = ok ? Shell.run(Shell.STATUS) : "";
            main.post(() -> {
                if (!ok) {
                    tokenState.setText("Could not save the token.");
                    output.setText(saved);
                    return;
                }
                tokenField.setText(""); // the app keeps no copy; the helper's file is the only one
                output.setText(status);
                showTokenState(status);
                if (!token.startsWith("fc1_")) {
                    tokenState.append("\n(Faceclaw tokens normally start with fc1_.)");
                }
            });
        }, "save-token").start();
    }

    /** Reads the Faceclaw ping line (and the stt line) from g2-ring-remote's status output. */
    private void showTokenState(String status) {
        showSttState(status);
        if (status.contains("g2-ring-remote is not running")) {
            tokenState.setText("Token: start g2-ring-remote to check it.");
            return;
        }
        Matcher m = PING.matcher(status);
        if (!m.find()) {
            return;
        }
        String ping = m.group(1);
        if (ping.equals("ok")) {
            tokenState.setText("Token: saved, and Faceclaw accepts it ✓");
        } else if (ping.contains("no token file") || ping.contains("is empty")) {
            tokenState.setText("Token: none saved yet.");
        } else if (ping.startsWith("unauthorized") || ping.startsWith("forbidden")) {
            tokenState.setText("Token: Faceclaw rejects the saved token (" + ping
                    + "). Create a new one and paste it here.");
        } else if (ping.contains("ECONNREFUSED") || ping.contains("failed to connect")) {
            tokenState.setText("Token: saved, but Faceclaw is not listening. Open Faceclaw; "
                    + "it listens while at least one input token exists.");
        } else {
            tokenState.setText("Token: Faceclaw says " + ping);
        }
        // A valid token is not enough: stock Faceclaw refuses input until glasses connect.
        Matcher last = LAST_DELIVERY.matcher(status);
        if (last.find() && last.group(1).contains("not ready for input")) {
            tokenState.append("\nBut Faceclaw refuses ring presses for now (\"not ready for input\"). "
                    + "Stock Faceclaw only takes input while the glasses are connected, not in "
                    + "Preview mode.");
        }
    }

    private void showState() {
        main.post(() -> {
            boolean running = Shell.shizukuRunning();
            boolean permitted = Shell.permitted();
            String text;
            if (!running) {
                text = "Shizuku is not running. Open Shizuku and start it (Wireless debugging); "
                        + "g2-ring-remote starts automatically once it is up.";
            } else if (!permitted) {
                text = "Shizuku is running. Grant G2 Ring Remote access to continue.";
            } else {
                text = "Shizuku is running and G2 Ring Remote has access.";
            }
            state.setText(text);
            grant.setVisibility(running && !permitted ? View.VISIBLE : View.GONE);
            saveToken.setEnabled(permitted);
            saveDeepgram.setEnabled(permitted);
            start.setEnabled(permitted);
            stop.setEnabled(permitted);
            refresh.setEnabled(permitted);
        });
    }

    private void runAsync(String busy, String command) {
        output.setText(busy);
        new Thread(() -> {
            String result = Shell.run(command);
            main.post(() -> {
                output.setText(result);
                showTokenState(result);
            });
        }, "shell").start();
    }

    private TextView heading(String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextSize(18);
        h.setPadding(0, dp(20), 0, dp(4));
        return h;
    }

    private Button button(LinearLayout parent, String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        parent.addView(b);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
