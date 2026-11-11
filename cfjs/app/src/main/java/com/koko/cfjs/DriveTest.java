package com.koko.cfjs;

import android.os.Build;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 网盘自检: on a drive-backed site (玩偶 by default) runs home -> detail -> playerContent on every drive line
 * (夸克/UC/阿里/115/百度/天翼/123/迅雷/移动) and fetches the first 64 KB of each play URL the way SenPlayer would
 * (spider proxy through 127.0.0.1:9978/proxy, or the direct link with the spider's headers).
 * It shows whether the problem is login (empty url / error), the proxy (status) or the link itself.
 * Header values (Cookie...) are never written; only their names. Report: Download/cfjs-netdisk-*.txt.
 * Run it from the 网盘登录 page in the foreground, so a spider that wants to show a QR dialog can.
 */
final class DriveTest {

    private static final long CALL_MS = 90_000;
    private static final int MAX_LINES = 10;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private final StringBuilder report = new StringBuilder();
    private final String preferred;

    private DriveTest(String preferred) {
        this.preferred = preferred;
    }

    static boolean running() {
        return RUNNING.get();
    }

    static void start(String siteKey) {
        if (!RUNNING.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                new DriveTest(siteKey).run();
            } finally {
                RUNNING.set(false);
            }
        }, "cfjs-drivetest").start();
    }

    /** 玩偶 first (the user's drive site), then any site whose api says Wogg. */
    static SiteInfo pickSite(String preferred) {
        if (preferred != null && !preferred.isEmpty()) {
            SiteInfo site = Engine.get().site(preferred);
            if (site != null && site.supported()) return site;
        }
        SiteInfo byApi = null;
        for (SiteInfo site : Engine.get().sites()) {
            if (!site.supported()) continue;
            if (site.key.equals("玩偶") || site.name.contains("玩偶")) return site;
            if (byApi == null && site.api.toLowerCase(Locale.ROOT).contains("wogg")) byApi = site;
        }
        return byApi;
    }

    private void run() {
        line("CFJS 网盘自检 " + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(new Date()));
        line("device=" + Build.MANUFACTURER + " " + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " jellyfin=" + JellyfinServer.running());
        line(NetDisk.storageStatus());
        try {
            body();
        } catch (Throwable e) {
            line("ABORT " + e);
        }
        line("saved=" + SelfTest.save("cfjs-netdisk-", report.toString()));
    }

    private void body() throws Exception {
        if (!Engine.get().loaded()) {
            String url = Prefs.configUrl();
            if (url.isEmpty()) throw new BridgeException("CONFIG_EMPTY", "请先填写配置地址");
            Engine.get().load(url);
        }
        SiteInfo site = pickSite(preferred);
        if (site == null) {
            line("FAIL 配置里没有玩偶 / Wogg 站点");
            return;
        }
        line("site=" + site.key + " " + site.name + " api=" + site.api + " ext=" + (site.ext.isEmpty() ? "(none)" : NetDisk.cut(site.ext, 120)) + " activityInFront=" + (App.activity() != null));
        JsonObject home = SpiderBridge.get().home(site.key, CALL_MS).value;
        JsonArray list = SpiderBridge.array(home, "list");
        JsonArray types = SpiderBridge.array(home, "class");
        line("home class=" + types.size() + " list=" + list.size());
        JsonObject vod = first(list);
        if (vod == null && types.size() > 0) {
            String tid = Json.safeString(types.get(0).getAsJsonObject(), "type_id");
            JsonObject category = SpiderBridge.get().category(site.key, tid, 1, new HashMap<>(), CALL_MS).value;
            vod = first(SpiderBridge.array(category, "list"));
            line("category " + tid + " list=" + SpiderBridge.array(category, "list").size());
        }
        if (vod == null) {
            line("FAIL 没有可用的影片 id");
            return;
        }
        String id = Json.safeString(vod, "vod_id");
        line("detail " + Json.safeString(vod, "vod_name") + " id=" + NetDisk.cut(id, 120));
        JsonObject detail = SpiderBridge.get().detail(site.key, id, CALL_MS).value;
        JsonArray items = SpiderBridge.array(detail, "list");
        if (items.size() == 0) {
            line("FAIL detail 为空 raw=" + NetDisk.cut(detail.toString(), 300));
            return;
        }
        JsonObject item = items.get(0).getAsJsonObject();
        JsonArray flags = item.getAsJsonArray("cfjs_flags");
        line("vod_play_from=" + NetDisk.cut(Json.safeString(item, "vod_play_from"), 300));
        line("lines=" + (flags == null ? 0 : flags.size()) + " content=" + NetDisk.cut(Json.safeString(item, "vod_content"), 120));
        if (flags == null || flags.size() == 0) {
            line("FAIL 没有播放线路（网盘站一般要先登录网盘才能列出文件）");
            return;
        }
        int tested = 0;
        for (JsonElement element : flags) {
            if (tested >= MAX_LINES) break;
            JsonObject flag = element.getAsJsonObject();
            String name = Json.safeString(flag, "flag");
            JsonArray episodes = flag.getAsJsonArray("episodes");
            String drive = NetDisk.driveOf(name);
            line("-- line " + name + " drive=" + (drive.isEmpty() ? "?" : drive) + " episodes=" + (episodes == null ? 0 : episodes.size()));
            if (episodes == null || episodes.size() == 0) continue;
            tested++;
            JsonObject episode = episodes.get(0).getAsJsonObject();
            play(site, name, Json.safeString(episode, "id"));
        }
    }

    private void play(SiteInfo site, String flag, String episodeId) {
        JsonObject p;
        try {
            p = SpiderBridge.get().play(site.key, flag, episodeId, CALL_MS).value;
        } catch (BridgeException e) {
            line("   play FAIL " + e.code + " " + e.getMessage());
            return;
        }
        String url = Json.safeString(p, "url");
        JsonElement raw = p.get("raw");
        JsonObject rawCopy = raw != null && raw.isJsonObject() ? raw.getAsJsonObject().deepCopy() : new JsonObject();
        rawCopy.remove("header");
        rawCopy.remove("url");
        line("   play url=" + NetDisk.cut(url, 160) + " urls=" + SpiderBridge.array(p, "urls").size() + " parse=" + Json.safeString(p, "parse")
                + " jx=" + Json.safeString(p, "jx") + " headerKeys=" + SelfTest.headerKeys(p.get("header")) + " direct=" + Json.safeString(p, "directPlayable")
                + (p.has("notDirectReason") ? " (" + Json.safeString(p, "notDirectReason") + ")" : "") + " otherRaw=" + NetDisk.cut(rawCopy.toString(), 200));
        if (url.isEmpty()) {
            line("   => 没有播放地址：网盘未登录、Cookie 失效或分享失效（看上面 otherRaw 里的 msg）");
            return;
        }
        for (String text : probe(url, p.get("header")).split("\n")) line("   " + text);
    }

    /** First 64 KB, as SenPlayer would request it. Returns report lines (header values are never included). */
    static String probe(String url, JsonElement header) {
        String target = url.startsWith("proxy://") ? JellyfinServer.BASE + "/proxy?" + url.substring(8) : url.replaceFirst("^http://[^/:]+:" + JellyfinServer.PORT + "/proxy", JellyfinServer.BASE + "/proxy");
        boolean local = target.startsWith(JellyfinServer.BASE + "/");
        if (local && !JellyfinServer.running()) return "probe skipped: 服务未启动（本地代理地址要靠 9978 服务，先点主页「启动服务」）";
        if (!target.startsWith("http")) return "probe skipped: scheme " + NetDisk.cut(target, 40);
        Request.Builder builder = new Request.Builder().url(target).header("Range", "bytes=0-65535");
        if (!local && header != null && header.isJsonObject()) {
            Map<String, String> map = Json.toMap(header);
            for (Map.Entry<String, String> e : map.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        long start = System.currentTimeMillis();
        try (Response response = OkHttp.client(60_000).newCall(builder.build()).execute()) {
            byte[] head = new byte[16];
            int n = 0;
            long total = 0;
            if (response.body() != null) {
                InputStream in = response.body().byteStream();
                byte[] buffer = new byte[8192];
                int r;
                while (total < 65536 && (r = in.read(buffer)) > 0) {
                    if (n < head.length) {
                        int c = Math.min(head.length - n, r);
                        System.arraycopy(buffer, 0, head, n, c);
                        n += c;
                    }
                    total += r;
                }
            }
            String text = "probe " + (local ? "via 9978/proxy" : "direct") + " -> " + response.code() + " type=" + response.header("Content-Type", "?") + " len=" + response.header("Content-Length", "?")
                    + " range=" + response.header("Content-Range", "-") + " got=" + total + "B in " + (System.currentTimeMillis() - start) + "ms head=" + sig(head, n);
            if (response.code() >= 400) text += "\n=> 地址打不开：" + (local ? "看 [PROXY] 行里 Spider 的报错" : "直链过期或缺少 Cookie/Referer");
            return text;
        } catch (Exception e) {
            return "probe FAIL " + e;
        }
    }

    private static String sig(byte[] head, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int c = head[i] & 0xff;
            b.append(c >= 32 && c < 127 ? String.valueOf((char) c) : ".");
        }
        return b.toString();
    }

    private static JsonObject first(JsonArray list) {
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = element.getAsJsonObject();
            String id = Json.safeString(vod, "vod_id");
            if (!id.isEmpty() && !id.startsWith("msearch:") && Json.safeString(vod, "action").isEmpty()) return vod;
        }
        return null;
    }

    private void line(String text) {
        report.append(text).append('\n');
        NetDisk.i(text);
    }
}
