package com.koko.cfjs;

import android.os.Build;
import android.os.Environment;

import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * Cloud-drive (网盘) helpers: which play lines are drive lines, the shared storage state that drive spiders
 * depend on (upstream catvod {@link Path#root()} = external storage root, {@link Path#tv()} = root/TV), and
 * the [NETDISK] log prefix so a device log shows exactly where a drive site stops.
 */
public final class NetDisk {

    private static final String[][] DRIVES = {
            {"夸克", "quark"}, {"UC", "uc", "优熙"}, {"阿里", "ali", "aliyun", "alipan"}, {"115"},
            {"百度", "baidu"}, {"天翼", "189", "tianyi", "天逸"}, {"123"}, {"迅雷", "xunlei", "thunder", "讯蕾"},
            {"移动", "139", "yidong", "异动", "和彩云"}, {"PikPak", "pikpak"}
    };

    private NetDisk() {
    }

    public static void i(String text) {
        BridgeLog.i("[NETDISK] " + text);
    }

    public static void w(String text) {
        BridgeLog.w("[NETDISK] " + text);
    }

    /** Drive name for a play-line / site name, or "" when it does not look like a cloud-drive line. */
    public static String driveOf(String text) {
        if (text == null || text.isEmpty()) return "";
        String low = text.toLowerCase(Locale.ROOT);
        for (String[] drive : DRIVES) {
            for (String word : drive) {
                String w = word.toLowerCase(Locale.ROOT);
                if (w.length() <= 3 && w.matches("[a-z0-9]+")) {
                    // short latin words (uc, 115, 123, 189, ali) must stand alone, not inside other words
                    if (low.matches(".*(^|[^a-z0-9])" + w + "([^a-z0-9]|$).*")) return drive[0];
                } else if (low.contains(w)) {
                    return drive[0];
                }
            }
        }
        return "";
    }

    /** Config / login / "my drive" sites: shown first in 网盘登录. */
    public static boolean isLoginSite(SiteInfo site) {
        String text = (site.key + " " + site.name + " " + site.api).toLowerCase(Locale.ROOT);
        return text.contains("config") || text.contains("配置") || text.contains("mypan") || text.contains("我的") || text.contains("扫码")
                || text.contains("login") || text.contains("登录") || text.contains("drive") || text.contains("wexwo");
    }

    public static boolean allFilesGranted() {
        return Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager();
    }

    private static volatile long checkedAt;
    private static volatile boolean writable;

    /** True when the app may write under the shared storage root the way upstream FongMi does (checked at most every 5 s). */
    public static boolean storageWritable() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (checkedAt != 0 && now - checkedAt < 5000) return writable;
        writable = probeWritable();
        checkedAt = now;
        return writable;
    }

    private static boolean probeWritable() {
        try {
            File dir = Path.tv();
            if (dir == null || !dir.isDirectory()) return false;
            File probe = new File(dir, ".cfjs-probe");
            try (FileOutputStream out = new FileOutputStream(probe)) {
                out.write(1);
            }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static String storageStatus() {
        String root;
        try {
            root = Path.rootPath();
        } catch (Throwable e) {
            root = "? " + e;
        }
        String grant = Build.VERSION.SDK_INT >= 30 ? (allFilesGranted() ? "所有文件访问=已授予" : "所有文件访问=未授予") : "sdk" + Build.VERSION.SDK_INT;
        return "存储 " + root + "/TV " + (storageWritable() ? "可写" : "不可写") + "，" + grant;
    }

    public static void logStorage(String when) {
        String text = storageStatus();
        if (storageWritable()) i(when + " " + text);
        else w(when + " " + text + "（网盘 Spider 若把登录信息存到 /sdcard/TV 会失败，请在「网盘登录」里授予所有文件访问权限）");
    }

    static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
