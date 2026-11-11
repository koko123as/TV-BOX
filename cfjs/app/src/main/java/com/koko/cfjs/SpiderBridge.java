package com.koko.cfjs;

import android.os.SystemClock;

import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Calls Java spiders the way upstream SiteApi does for type 3 sites, without any Activity:
 * home = homeContent(true) + homeVideoContent; category/search/detail/player pass straight through;
 * player result keeps the spider's own fields, sets flag when empty and site headers when the spider gave none.
 * One running task per site (three for the Jellyfin instance); a timed-out task keeps the permit until it really ends.
 */
public final class SpiderBridge {

    public static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);
    private static final SpiderBridge INSTANCE = new SpiderBridge(1, "cfjs-spider-");
    /** Separate instance for the Jellyfin server: SenPlayer opens several rows of one site at once. */
    private static final SpiderBridge JELLYFIN = new SpiderBridge(3, "cfjs-jf-");
    private final ConcurrentHashMap<String, Semaphore> permits = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final int perSite;

    /** Instance used by the token API on 19978: one running task per site. */
    public static SpiderBridge get() {
        return INSTANCE;
    }

    /** Instance used by the Jellyfin server on 9978: up to 3 running tasks per site. */
    public static SpiderBridge jellyfin() {
        return JELLYFIN;
    }

    private SpiderBridge(int perSite, String prefix) {
        this.perSite = perSite;
        AtomicInteger count = new AtomicInteger();
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, prefix + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public static final class Timed {
        public final JsonObject value;
        public final long elapsedMs;

        Timed(JsonObject value, long elapsedMs) {
            this.value = value;
            this.elapsedMs = elapsedMs;
        }
    }

    public SiteInfo requireSite(String key) throws BridgeException {
        if (!Engine.get().loaded()) throw new BridgeException("CONFIG_EMPTY", "还没有加载配置");
        if (key == null || key.isEmpty()) throw new BridgeException("BAD_REQUEST", "site is required");
        SiteInfo site = Engine.get().site(key);
        if (site == null) throw new BridgeException("SITE_NOT_FOUND", "Unknown site: " + key);
        if (!site.supported()) throw new BridgeException("UNSUPPORTED_SITE", "v1 只支持 Java 站点: " + key + " (" + site.kind() + ")");
        return site;
    }

    /** True while any call of this instance is running on the site. */
    public boolean busy(String key) {
        Semaphore permit = key == null ? null : permits.get(key);
        return permit != null && permit.availablePermits() < perSite;
    }

    public Timed home(String key) throws BridgeException {
        return home(key, TIMEOUT_MS);
    }

    public Timed category(String key, String tid, int page, Map<String, String> filters) throws BridgeException {
        return category(key, tid, page, filters, TIMEOUT_MS);
    }

    public Timed search(String key, String keyword, int page) throws BridgeException {
        return search(key, keyword, page, TIMEOUT_MS);
    }

    public Timed detail(String key, String id) throws BridgeException {
        return detail(key, id, TIMEOUT_MS);
    }

    public Timed play(String key, String flag, String id) throws BridgeException {
        return play(key, flag, id, TIMEOUT_MS);
    }

    public Timed home(String key, long timeoutMs) throws BridgeException {
        SiteInfo site = requireSite(key);
        return run(site, "home", timeoutMs, () -> {
            Spider spider = Engine.get().spider(site);
            JsonObject home = object(spider.homeContent(true));
            JsonObject video = object(spider.homeVideoContent());
            JsonArray list = array(video, "list");
            if (list.size() > 0) home.add("list", list);
            filterCategories(site, home);
            return home;
        });
    }

    public Timed category(String key, String tid, int page, Map<String, String> filters, long timeoutMs) throws BridgeException {
        SiteInfo site = requireSite(key);
        if (tid == null || tid.isEmpty()) throw new BridgeException("BAD_REQUEST", "tid is required");
        HashMap<String, String> extend = filters == null ? new HashMap<>() : new HashMap<>(filters);
        String pg = String.valueOf(Math.max(page, 1));
        // Upstream TypeFragment (mobile and leanback) always calls categoryContent with filter=true.
        return run(site, "category", timeoutMs, () -> object(Engine.get().spider(site).categoryContent(tid, pg, true, extend)));
    }

    public Timed search(String key, String keyword, int page, long timeoutMs) throws BridgeException {
        SiteInfo site = requireSite(key);
        if (!site.searchable) throw new BridgeException("UNSUPPORTED_SITE", "站点不可搜索: " + key);
        if (keyword == null || keyword.trim().isEmpty()) throw new BridgeException("BAD_REQUEST", "wd is required");
        String pg = String.valueOf(Math.max(page, 1));
        // Upstream uses the two-argument searchContent on page 1 and the paged overload after it.
        return run(site, "search", timeoutMs, () -> {
            Spider spider = Engine.get().spider(site);
            return object("1".equals(pg) ? spider.searchContent(keyword, false) : spider.searchContent(keyword, false, pg));
        });
    }

    public Timed detail(String key, String id, long timeoutMs) throws BridgeException {
        SiteInfo site = requireSite(key);
        if (id == null || id.isEmpty()) throw new BridgeException("BAD_REQUEST", "id is required");
        return run(site, "detail", timeoutMs, () -> {
            JsonObject result = object(Engine.get().spider(site).detailContent(Collections.singletonList(id)));
            JsonArray list = array(result, "list");
            if (list.size() > 0 && list.get(0).isJsonObject()) list.get(0).getAsJsonObject().add("cfjs_flags", Episodes.flags(list.get(0).getAsJsonObject()));
            return result;
        });
    }

    public Timed play(String key, String flag, String id, long timeoutMs) throws BridgeException {
        SiteInfo site = requireSite(key);
        if (flag == null || id == null || id.isEmpty()) throw new BridgeException("BAD_REQUEST", "flag and id are required");
        return run(site, "play", timeoutMs, () -> PlayInfo.from(object(Engine.get().spider(site).playerContent(flag, id, Engine.get().flags())), flag, site));
    }

    /** Upstream SiteApi.action: items carrying an "action" field (config / drive-login entries) call spider.action. */
    public Timed action(String key, String action, long timeoutMs) throws BridgeException {
        SiteInfo site = requireSite(key);
        if (action == null || action.isEmpty()) throw new BridgeException("BAD_REQUEST", "action is required");
        return run(site, "action", timeoutMs, () -> object(Engine.get().spider(site).action(action)));
    }

    private static void filterCategories(SiteInfo site, JsonObject home) {
        if (site.categories.isEmpty()) return;
        JsonArray types = array(home, "class");
        JsonArray kept = new JsonArray();
        for (String name : site.categories) {
            for (JsonElement type : types) {
                if (type.isJsonObject() && name.equals(Json.safeString(type.getAsJsonObject(), "type_name"))) kept.add(type);
            }
        }
        if (kept.size() > 0) home.add("class", kept);
    }

    static JsonObject object(String json) {
        try {
            JsonElement element = Json.parse(json == null ? "" : json);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static void cancel(Future<?> future, AtomicBoolean started, Semaphore permit) {
        future.cancel(true);
        if (!started.getAndSet(true)) permit.release();
    }

    private Timed run(SiteInfo site, String op, long timeoutMs, Callable<JsonObject> callable) throws BridgeException {
        Semaphore permit = permits.computeIfAbsent(site.key, key -> new Semaphore(perSite));
        long start = SystemClock.elapsedRealtime();
        try {
            if (!permit.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) throw new BridgeException("BUSY", "站点忙: " + site.key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BridgeException("CANCELLED", "interrupted", e);
        }
        AtomicBoolean started = new AtomicBoolean();
        Future<JsonObject> future;
        try {
            future = executor.submit(() -> {
                if (started.getAndSet(true)) return null;
                try {
                    return callable.call();
                } finally {
                    permit.release();
                }
            });
        } catch (RuntimeException e) {
            permit.release();
            throw new BridgeException("SPIDER_ERROR", "cannot schedule " + op, e);
        }
        long remaining = Math.max(1, timeoutMs - (SystemClock.elapsedRealtime() - start));
        try {
            JsonObject value = future.get(remaining, TimeUnit.MILLISECONDS);
            long elapsed = SystemClock.elapsedRealtime() - start;
            BridgeLog.i(op + " site=" + site.key + " ok " + elapsed + "ms");
            return new Timed(value == null ? new JsonObject() : value, elapsed);
        } catch (TimeoutException e) {
            cancel(future, started, permit);
            BridgeLog.w(op + " site=" + site.key + " TIMEOUT");
            throw new BridgeException("TIMEOUT", op + " 超过 " + timeoutMs + "ms", e);
        } catch (InterruptedException e) {
            cancel(future, started, permit);
            Thread.currentThread().interrupt();
            throw new BridgeException("CANCELLED", op + " cancelled", e);
        } catch (CancellationException e) {
            throw new BridgeException("CANCELLED", op + " cancelled", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            while (cause.getCause() != null && cause.getCause() != cause && (cause instanceof IllegalStateException || cause instanceof java.lang.reflect.InvocationTargetException)) cause = cause.getCause();
            BridgeLog.w(op + " site=" + site.key + " failed: " + cause);
            throw new BridgeException("SPIDER_ERROR", cause.getClass().getName() + ": " + cause.getMessage(), cause);
        }
    }
}
