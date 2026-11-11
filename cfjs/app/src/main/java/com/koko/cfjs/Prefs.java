package com.koko.cfjs;

import android.content.Context;
import android.content.SharedPreferences;

import com.fongmi.android.tv.App;

import java.security.SecureRandom;
import java.util.Locale;

/** Small settings store: config URL and the API token (generated once on the device, never committed). */
public final class Prefs {

    private Prefs() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences("cfjs", Context.MODE_PRIVATE);
    }

    public static String configUrl() {
        return prefs().getString("config_url", "");
    }

    public static void configUrl(String url) {
        prefs().edit().putString("config_url", url == null ? "" : url.trim()).apply();
    }

    public static synchronized String token() {
        String token = prefs().getString("token", "");
        if (token.isEmpty()) {
            token = newToken();
            prefs().edit().putString("token", token).apply();
        }
        return token;
    }

    public static synchronized String resetToken() {
        String token = newToken();
        prefs().edit().putString("token", token).apply();
        return token;
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) builder.append(String.format(Locale.ROOT, "%02x", b));
        return builder.toString();
    }
}
