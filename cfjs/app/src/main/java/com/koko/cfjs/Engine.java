package com.koko.cfjs;

import com.fongmi.android.tv.api.loader.JarLoader;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads one VOD config and owns the upstream {@link JarLoader}. It follows upstream VodConfig for the
 * parts a Java spider needs: global spider JAR (with ;md5;), per-site jar, ext, headers, hosts and proxy.
 * Depot configs ({@code urls}) are rejected with a clear message.
 */
public final class Engine {

    private static final Engine INSTANCE = new Engine();
    private volatile JarLoader loader = new JarLoader();
    private volatile Map<String, SiteInfo> sites = Collections.emptyMap();
    private volatile List<String> flags = Collections.emptyList();
    private volatile String url = "";
    private volatile long generation;

    public static Engine get() {
        return INSTANCE;
    }

    public String url() {
        return url;
    }

    /** Changes every time a config is applied or released, so caches built on the old sites can be dropped. */
    public long generation() {
        return generation;
    }

    public boolean loaded() {
        return !sites.isEmpty();
    }

    public List<SiteInfo> sites() {
        return new ArrayList<>(sites.values());
    }

    public SiteInfo site(String key) {
        return key == null ? null : sites.get(key);
    }

    public List<String> flags() {
        return flags;
    }

    /** Downloads and applies the config. Must not run on the main thread. */
    public synchronized void load(String configUrl) throws Exception {
        long start = System.currentTimeMillis();
        String text = fetch(configUrl);
        JsonElement parsed = Json.parse(text);
        if (parsed == null || !parsed.isJsonObject()) throw new BridgeException("CONFIG_INVALID", "配置不是 JSON 对象（可能是加密配置，v1 不支持）");
        JsonObject object = parsed.getAsJsonObject();
        if (object.has("urls")) throw new BridgeException("CONFIG_DEPOT", "这是多仓配置（urls），请填写其中一个单仓地址");
        if (object.has("msg")) throw new BridgeException("CONFIG_MSG", Json.safeString(object, "msg"));
        String spider = Json.safeString(object, "spider");
        Map<String, SiteInfo> items = new LinkedHashMap<>();
        for (JsonElement element : Json.safeListElement(object, "sites")) {
            SiteInfo site = SiteInfo.from(element, spider);
            if (site != null && !items.containsKey(site.key)) items.put(site.key, site);
        }
        if (items.isEmpty()) throw new BridgeException("CONFIG_EMPTY", "配置里没有 sites");
        JarLoader fresh = new JarLoader();
        if (!spider.isEmpty()) {
            String key = Crypto.md5(spider);
            fresh.parseJar(key, spider);
            fresh.setRecent(key);
        }
        applyNetwork(object);
        JarLoader old = loader;
        loader = fresh;
        sites = items;
        flags = Json.safeListString(object, "flags");
        url = configUrl;
        generation++;
        retire(old);
        long java = items.values().stream().filter(SiteInfo::supported).count();
        BridgeLog.i("config loaded sites=" + items.size() + " java=" + java + " in " + (System.currentTimeMillis() - start) + "ms");
    }

    /** Up to 3 tries, keeping the real network error (OkHttp.string swallows it). */
    private static String fetch(String configUrl) throws BridgeException {
        if (!configUrl.startsWith("http")) throw new BridgeException("CONFIG_INVALID", "配置地址要以 http 开头");
        String last = "";
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (okhttp3.Response response = OkHttp.newCall(configUrl).execute()) {
                String body = response.body().string();
                if (response.isSuccessful() && !body.trim().isEmpty()) return body;
                last = "HTTP " + response.code() + " 长度 " + body.length();
            } catch (Exception e) {
                last = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            BridgeLog.w("config fetch " + attempt + "/3 failed: " + last);
            try {
                Thread.sleep(1000L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new BridgeException("CONFIG_FETCH", "配置下载失败（" + last + "）");
    }

    /** Replaces (not adds to) the global headers, proxy rules and hosts, so a reload does not pile them up. */
    private void applyNetwork(JsonObject object) {
        OkHttp.responseInterceptor().clear();
        OkHttp.selector().clear();
        OkHttp.dns().clear();
        OkHttp.responseInterceptor().addAll(Header.arrayFrom(array(object, "headers")));
        OkHttp.selector().addAll(Proxy.arrayFrom(array(object, "proxy")));
        OkHttp.dns().addAll(Json.safeListString(object, "hosts"));
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static void retire(JarLoader old) {
        new Thread(() -> {
            try {
                old.destroy();
            } catch (Throwable e) {
                BridgeLog.w("old loader destroy: " + e);
            }
        }, "cfjs-retire").start();
    }

    /** Same path as upstream {@code site.recent().spider()} for a Java site. */
    public Spider spider(SiteInfo site) {
        JarLoader current = loader;
        current.setRecent(Crypto.md5(site.jar));
        return current.getSpider(site.key, site.api, site.ext, site.jar);
    }

    /** CatVod local proxy without siteKey: the recent jar's Proxy.proxy, then the others (upstream BaseLoader.proxy). */
    public Object[] proxy(Map<String, String> params) throws Exception {
        return loader.proxy(params);
    }

    /** Releases every spider (upstream VodConfig.destroy path). */
    public synchronized void shutdown() {
        JarLoader old = loader;
        loader = new JarLoader();
        sites = Collections.emptyMap();
        generation++;
        retire(old);
    }
}
