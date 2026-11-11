package com.koko.cfjs;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** In-memory ring buffer of CFJS log lines, mirrored to logcat under tag CFJS. */
public final class BridgeLog {

    public static final String TAG = "CFJS";
    private static final int MAX = 500;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    private BridgeLog() {
    }

    public static void i(String text) {
        Log.i(TAG, text);
        add("I", text);
    }

    public static void w(String text) {
        Log.w(TAG, text);
        add("W", text);
    }

    private static synchronized void add(String level, String text) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        LINES.addLast(time + " " + level + " " + text);
        while (LINES.size() > MAX) LINES.removeFirst();
    }

    public static synchronized String dump() {
        StringBuilder builder = new StringBuilder();
        for (String line : LINES) builder.append(line).append('\n');
        return builder.toString();
    }
}
