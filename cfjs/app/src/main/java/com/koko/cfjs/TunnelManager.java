package com.koko.cfjs;

import com.fongmi.android.tv.App;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs cloudflared, packaged as {@code lib/arm64-v8a/libcloudflared.so} and executed from nativeLibraryDir
 * (Android does not allow executing files from app data). This stage supports Quick Tunnel only:
 * no account or domain, random https://*.trycloudflare.com host, pointing at the local API.
 */
public final class TunnelManager {

    private static final Pattern URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
    private static volatile Process process;
    private static volatile String publicUrl = "";

    private TunnelManager() {
    }

    public static File binary() {
        return new File(App.get().getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
    }

    /** Build note written by CI (source build for android, or the linux-arm64 release fallback). */
    public static String buildInfo() {
        try (InputStream in = App.get().getAssets().open("cloudflared.txt")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString("UTF-8").trim();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static boolean running() {
        Process current = process;
        return current != null && alive(current);
    }

    public static String publicUrl() {
        return running() ? publicUrl : "";
    }

    public static synchronized void start() {
        if (running()) return;
        File bin = binary();
        if (!bin.exists()) {
            BridgeLog.w("cloudflared not packaged: " + bin.getAbsolutePath());
            return;
        }
        if (!BridgeServer.startServer()) return;
        publicUrl = "";
        Thread thread = new Thread(() -> launch(bin), "cfjs-tunnel");
        thread.setDaemon(true);
        thread.start();
    }

    private static void launch(File bin) {
        Process started = null;
        try {
            BridgeLog.i("cloudflared build: " + buildInfo());
            ProcessBuilder builder = new ProcessBuilder(bin.getAbsolutePath(), "tunnel", "--no-autoupdate", "--protocol", "http2", "--url", "http://" + BridgeServer.HOST + ":" + BridgeServer.PORT);
            builder.redirectErrorStream(true);
            builder.environment().put("HOME", App.get().getFilesDir().getAbsolutePath());
            builder.directory(App.get().getFilesDir());
            synchronized (TunnelManager.class) {
                started = builder.start();
                process = started;
            }
            BridgeLog.i("cloudflared started");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    BridgeLog.i("[cf] " + line);
                    Matcher matcher = URL.matcher(line);
                    if (publicUrl.isEmpty() && matcher.find()) {
                        publicUrl = matcher.group();
                        BridgeLog.i("Tunnel URL " + publicUrl);
                    }
                }
            }
            BridgeLog.i("cloudflared exited code=" + started.waitFor());
        } catch (Throwable e) {
            BridgeLog.w("cloudflared failed: " + e);
        } finally {
            synchronized (TunnelManager.class) {
                if (process == started) {
                    process = null;
                    publicUrl = "";
                }
            }
        }
    }

    public static void stop() {
        Process current;
        synchronized (TunnelManager.class) {
            current = process;
        }
        if (current == null) return;
        current.destroy();
        if (!waitFor(current, 5_000)) BridgeLog.w("cloudflared did not exit within 5s");
        BridgeLog.i("cloudflared stopped");
    }

    /** Process.isAlive() is API 26; minSdk is 24. */
    private static boolean alive(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static boolean waitFor(Process process, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (alive(process)) {
            if (System.currentTimeMillis() > end) return false;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
