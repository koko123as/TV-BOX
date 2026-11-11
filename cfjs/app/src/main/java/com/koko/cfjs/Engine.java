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

    public static Engine get() {
        return INSTANCE;
    }

    public String url() {
        return url;
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
        String text = OkHttp.string(configUrl);
        if (text == null || text.trim().isEmpty()) throw new BridgeException("CONFIG_EMPTY", "配置下载失败或为空：" + configUrl);
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
        retire(old);
        long java = items.values().stream().filter(SiteInfo::supported).count();
        BridgeLog.i("config loaded sites=" + items.size() + " java=" + java + " in " + (System.currentTimeMillis() - start) + "ms");
    }

    private void applyNetwork(JsonObject object) {
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

    /** Releases every spider (upstream VodConfig.destroy path). */
    public synchronized void shutdown() {
        JarLoader old = loader;
        loader = new JarLoader();
        sites = Collections.emptyMap();
        retire(old);
    }
}
