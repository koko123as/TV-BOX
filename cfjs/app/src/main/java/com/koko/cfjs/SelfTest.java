package com.koko.cfjs;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Feasibility self-test on real spiders, nothing mocked. Part 1 runs homeContent on the first {@link #SCAN}
 * Java sites to see how many Guard spiders work on this host. Part 2 takes the first site that passed and
 * runs category, detail, play and search on it. The report goes to Download/cfjs-selftest-*.txt.
 */
public final class SelfTest {

    private static final int SCAN = 8;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private final StringBuilder report = new StringBuilder();
    private int failures;

    public static boolean running() {
        return RUNNING.get();
    }

    public static void start() {
        if (!RUNNING.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                new SelfTest().run();
            } finally {
                RUNNING.set(false);
            }
        }, "cfjs-selftest").start();
    }

    private void run() {
        line("CFJS self-test " + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(new Date()));
        line("device=" + Build.MANUFACTURER + " " + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " abi=" + Build.SUPPORTED_ABIS[0]);
        line("package=" + App.get().getPackageName() + " service=" + SpiderService.isRunning());
        try {
            body();
        } catch (Throwable e) {
            failures++;
            line("ABORT " + e);
        }
        line("failures=" + failures);
        line("saved=" + save());
    }

    private void body() throws Exception {
        if (!Engine.get().loaded()) {
            String url = Prefs.configUrl();
            if (url.isEmpty()) throw new BridgeException("CONFIG_EMPTY", "请先填写配置地址");
            Engine.get().load(url);
        }
        List<SiteInfo> java = new ArrayList<>();
        int other = 0;
        for (SiteInfo site : Engine.get().sites()) {
            if (site.supported()) java.add(site);
            else other++;
        }
        line("sites java=" + java.size() + " other=" + other);
        line("== part 1: homeContent on the first " + Math.min(SCAN, java.size()) + " Java sites (activityResumed=" + (App.activity() != null) + ")");
        SiteInfo picked = null;
        JsonObject pickedHome = null;
        int ok = 0;
        for (SiteInfo site : java.subList(0, Math.min(SCAN, java.size()))) {
            JsonObject home = step("home " + site.key + " (" + site.api + ")", () -> SpiderBridge.get().home(site.key));
            if (home == null) continue;
            int classes = SpiderBridge.array(home, "class").size();
            int list = SpiderBridge.array(home, "list").size();
            line("     class=" + classes + " list=" + list);
            if (classes > 0 || list > 0) {
                ok++;
                if (picked == null) {
                    picked = site;
                    pickedHome = home;
                }
            }
        }
        line("part 1 result: " + ok + "/" + Math.min(SCAN, java.size()) + " returned content");
        if (picked == null) {
            failures++;
            line("FAIL no site returned home content; part 2 skipped");
            return;
        }
        deep(picked, pickedHome);
    }

    private void deep(SiteInfo site, JsonObject home) throws Exception {
        line("== part 2: " + site.key + " " + site.name + " (activityResumed=" + (App.activity() != null) + ")");
        String vodId = firstId(SpiderBridge.array(home, "list"));
        String vodName = firstName(SpiderBridge.array(home, "list"));
        JsonArray types = SpiderBridge.array(home, "class");
        if (types.size() > 0) {
            String tid = Json.safeString(types.get(0).getAsJsonObject(), "type_id");
            JsonObject category = step("category tid=" + tid, () -> SpiderBridge.get().category(site.key, tid, 1, new HashMap<>()));
            if (category != null) {
                JsonArray list = SpiderBridge.array(category, "list");
                line("     list=" + list.size() + " pagecount=" + Json.safeString(category, "pagecount"));
                if (vodId.isEmpty()) {
                    vodId = firstId(list);
                    vodName = firstName(list);
                }
            }
        }
        if (vodId.isEmpty()) {
            failures++;
            line("FAIL no vod id; detail/play skipped");
        } else {
            String id = vodId;
            JsonObject detail = step("detail id=" + id, () -> SpiderBridge.get().detail(site.key, id));
            JsonArray list = detail == null ? new JsonArray() : SpiderBridge.array(detail, "list");
            if (list.size() > 0) {
                JsonObject vod = list.get(0).getAsJsonObject();
                JsonArray flags = vod.getAsJsonArray("cfjs_flags");
                line("     name=" + Json.safeString(vod, "vod_name") + " flags=" + flags.size());
                JsonObject flag = null;
                for (JsonElement item : flags) {
                    if (item.getAsJsonObject().getAsJsonArray("episodes").size() > 0) {
                        flag = item.getAsJsonObject();
                        break;
                    }
                }
                if (flag == null) {
                    failures++;
                    line("FAIL no episode; play skipped");
                } else {
                    String flagName = Json.safeString(flag, "flag");
                    JsonObject episode = flag.getAsJsonArray("episodes").get(0).getAsJsonObject();
                    String episodeId = Json.safeString(episode, "id");
                    line("     flag=" + flagName + " episodes=" + flag.getAsJsonArray("episodes").size() + " first=" + Json.safeString(episode, "name"));
                    JsonObject play = step("play", () -> SpiderBridge.get().play(site.key, flagName, episodeId));
                    if (play != null) {
                        JsonObject summary = play.deepCopy();
                        summary.remove("raw");
                        line("     " + summary);
                    }
                }
            } else if (detail != null) {
                failures++;
                line("FAIL detail list empty");
            }
        }
        if (site.searchable && !vodName.isEmpty()) {
            String keyword = vodName;
            JsonObject search = step("search wd=" + keyword, () -> SpiderBridge.get().search(site.key, keyword, 1));
            if (search != null) line("     list=" + SpiderBridge.array(search, "list").size());
        }
        line("activityResumed(end)=" + (App.activity() != null));
    }

    private static String firstId(JsonArray list) {
        return list.size() > 0 && list.get(0).isJsonObject() ? Json.safeString(list.get(0).getAsJsonObject(), "vod_id") : "";
    }

    private static String firstName(JsonArray list) {
        return list.size() > 0 && list.get(0).isJsonObject() ? Json.safeString(list.get(0).getAsJsonObject(), "vod_name") : "";
    }

    private interface Call {
        SpiderBridge.Timed call() throws Exception;
    }

    private JsonObject step(String name, Call call) {
        try {
            SpiderBridge.Timed timed = call.call();
            line("OK   " + name + " " + timed.elapsedMs + "ms");
            return timed.value;
        } catch (BridgeException e) {
            failures++;
            line("FAIL " + name + " " + e.code + " " + e.getMessage());
        } catch (Throwable e) {
            failures++;
            line("FAIL " + name + " " + e);
        }
        return null;
    }

    private void line(String text) {
        report.append(text).append('\n');
        BridgeLog.i(text);
    }

    private String save() {
        String name = "cfjs-selftest-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt";
        byte[] bytes = report.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver resolver = App.get().getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("MediaStore insert failed");
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("MediaStore stream failed");
                    out.write(bytes);
                }
                return "下载/" + name;
            }
            File dir = App.get().getExternalFilesDir(null);
            File file = new File(dir == null ? App.get().getFilesDir() : dir, name);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
            return file.getAbsolutePath();
        } catch (Throwable e) {
            return "保存失败 " + e;
        }
    }
}
