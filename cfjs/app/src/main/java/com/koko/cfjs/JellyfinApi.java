package com.koko.cfjs;

import android.util.LruCache;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import okhttp3.Request;

/**
 * Jellyfin-compatible API, ported from SpiderHost 0.0.15 JellyfinApi.kt (already accepted by SenPlayer) onto cfjs's
 * {@link Engine} / {@link SpiderBridge}. Field names follow Jellyfin's public OpenAPI schema.
 *
 * Mapping:
 *   libraries          推荐 (home site homeContent list / homeVideoContent) + the home site's categories
 *                      (CollectionFolder, tvshows) + 「全部站点」 (folders: site -> category -> videos)
 *   vod                Series;  vod_play_from line -> Season;  episode -> Episode
 *   search             every searchable Java site in parallel (15 s), same titles merged into one 「[n源]」 entry
 *   msearch: vods      (豆瓣-style jump items) open as a merged entry of the same title found on the other sites
 *   playback           playerContent -> /Videos/{id}/stream: 302 to the source, or relayed through 127.0.0.1
 *                      with the headers when the source needs them (HLS rewritten so segments get them too)
 *   user data          favourites / progress / resume / next up kept locally in files/jf-user.json
 * Every request is logged with [JF]; anything unhandled is logged as "JF UNHANDLED" and answered 404.
 */
final class JellyfinApi {

    static final String SERVER_ID = "6c2e9d0f7a3b4d4cae8f2b3a4d5c6e7f";
    static final String USER_ID = "9a4f3e2d1c0b49e8b7d6c5f4e3a2b1d0";
    static final String TOKEN = "cfjsjellyfin00000000000000000001";
    private static final String VERSION = "10.10.7";
    private static final String REC_TID = "__rec__";
    private static final double POSTER_RATIO = 0.6667;
    private static final long HOME_MS = 15_000;
    private static final long CATEGORY_MS = 15_000;
    private static final long SEARCH_MS = 12_000;
    private static final long DETAIL_MS = 12_000;
    private static final long PLAY_MS = 45_000;
    private static final int MAX_GROUP = 16;
    private static final long DETAIL_FAIL_TTL = 60_000L;
    private static final String MSEARCH = "msearch:";

    private static final ExecutorService POOL = Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "cfjs-jf-fan-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private JellyfinApi() {
    }

    private static SpiderBridge bridge() {
        return SpiderBridge.jellyfin();
    }

    // ---------- id store: Jellyfin ids are 32-hex; map them back to spider coordinates ----------

    private static final ConcurrentHashMap<String, List<String>> IDS = new ConcurrentHashMap<>();
    private static volatile File idFile;
    private static volatile File imgDir;

    static synchronized void init(File files, File cache) {
        if (idFile != null) return;
        JfUserStore.init(files);
        File dir = new File(cache, "jf-img");
        if (!dir.isDirectory() && !dir.mkdirs()) BridgeLog.w("[JF] cannot create " + dir);
        imgDir = dir;
        File f = new File(files, "jf-ids.tsv");
        if (f.exists()) {
            try {
                for (String line : readText(f).split("\n")) {
                    String[] parts = line.split("\t", -1);
                    if (parts.length >= 2) IDS.put(parts[0], new ArrayList<>(Arrays.asList(parts).subList(1, parts.length)));
                }
            } catch (Exception e) {
                BridgeLog.w("[JF] read ids failed: " + e);
            }
        }
        idFile = f;
    }

    private static String idOf(String site, String... parts) {
        List<String> key = new ArrayList<>();
        key.add(site);
        key.addAll(Arrays.asList(parts));
        String id = md5(String.join("\u0001", key));
        if (IDS.putIfAbsent(id, key) == null) appendId(id, key);
        return id;
    }

    private static synchronized void appendId(String id, List<String> key) {
        File f = idFile;
        if (f == null) return;
        StringBuilder line = new StringBuilder(id);
        for (String part : key) line.append('\t').append(part.replace('\t', ' ').replace('\n', ' '));
        line.append('\n');
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            w.write(line.toString());
        } catch (Exception e) {
            BridgeLog.w("[JF] write id failed: " + e);
        }
    }

    private static String norm(String id) {
        return id == null ? "" : id.toLowerCase(Locale.ROOT).replace("-", "");
    }

    /** [siteKey, kind, ...] */
    private static List<String> lookup(String id) {
        return id == null ? null : IDS.get(norm(id));
    }

    private static String kind(List<String> key) {
        return key != null && key.size() > 1 ? key.get(1) : "";
    }

    private static String viewId(String site, String tid) {
        return idOf(site, "view", tid);
    }

    private static String seriesId(String site, String vodId) {
        return idOf(site, "series", vodId);
    }

    private static String seriesId(Vod v) {
        return seriesId(v.site, v.id);
    }

    private static String seasonId(String site, String vodId, int f) {
        return idOf(site, "season", vodId, String.valueOf(f));
    }

    private static String episodeId(String site, String vodId, int f, int e) {
        return idOf(site, "episode", vodId, String.valueOf(f), String.valueOf(e));
    }

    private static String sitesRootId() {
        return idOf("_", "sites");
    }

    private static String siteFolderId(String site) {
        return idOf(site, "site");
    }

    /** Search results with the same title from several sites, merged into one entry (like 影视仓's 多个源). */
    private static String groupId(List<Vod> members) {
        String[] parts = new String[members.size() + 1];
        parts[0] = "group";
        for (int i = 0; i < members.size(); i++) parts[i + 1] = members.get(i).site + "\u0002" + members.get(i).id;
        return idOf("_", parts);
    }

    // ---------- caches ----------

    static final class Vod {
        final String site, id, name, pic, remarks, year;

        Vod(String site, String id, String name, String pic, String remarks, String year) {
            this.site = site;
            this.id = id;
            this.name = name;
            this.pic = pic;
            this.remarks = remarks;
            this.year = year;
        }

        Vod withName(String n) {
            return new Vod(site, id, n, pic, remarks, year);
        }

        String key() {
            return site + "|" + id;
        }
    }

    static final class Detail {
        final Vod vod;
        final String content, actor, director;
        final List<String> flags;
        final List<List<String[]>> episodes;

        Detail(Vod vod, String content, String actor, String director, List<String> flags, List<List<String[]>> episodes) {
            this.vod = vod;
            this.content = content;
            this.actor = actor;
            this.director = director;
            this.flags = flags;
            this.episodes = episodes;
        }

        int count(int f) {
            return f >= 0 && f < episodes.size() ? episodes.get(f).size() : 0;
        }

        String[] episode(int f, int e) {
            return e >= 0 && e < count(f) ? episodes.get(f).get(e) : null;
        }
    }

    static final class Resolved {
        final List<String[]> qualities;
        final Map<String, String> headers;
        final long at;

        Resolved(List<String[]> qualities, Map<String, String> headers, long at) {
            this.qualities = qualities;
            this.headers = headers;
            this.at = at;
        }
    }

    static final class Home {
        final List<String[]> classes;
        final List<Vod> list;

        Home(List<String[]> classes, List<Vod> list) {
            this.classes = classes;
            this.list = list;
        }
    }

    private static final ConcurrentHashMap<String, Vod> VODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Detail> DETAILS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Resolved> RESOLVED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Home> HOMES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> PAGE_SIZES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object[]> PAGES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object[]> SEARCHES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<Detail>> GROUP_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<Vod>> MSEARCH_MEMBERS = new ConcurrentHashMap<>();
    /** site|vodId -> {group id, group name} once that vod has been shown inside a merged entry. */
    private static final ConcurrentHashMap<String, String[]> OWNERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CompletableFuture<Detail>> INFLIGHT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> DETAIL_FAILED_AT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object[]> QUALITY_IDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> LAST_PROGRESS_LOG = new ConcurrentHashMap<>();
    private static volatile long generation = -1;

    /** A new config means new sites: drop everything built on the old one (ids and user data stay). */
    private static void checkGeneration() {
        long now = Engine.get().generation();
        if (now == generation) return;
        synchronized (JellyfinApi.class) {
            if (now == generation) return;
            VODS.clear();
            DETAILS.clear();
            RESOLVED.clear();
            HOMES.clear();
            PAGE_SIZES.clear();
            PAGES.clear();
            SEARCHES.clear();
            GROUP_CACHE.clear();
            MSEARCH_MEMBERS.clear();
            OWNERS.clear();
            DETAIL_FAILED_AT.clear();
            generation = now;
        }
    }

    /** SenPlayer asks for this on the home screen; zero counts are what an empty Jellyfin library reports. */
    private static JsonObject itemCounts() {
        O o = new O();
        for (String k : new String[]{"MovieCount", "SeriesCount", "EpisodeCount", "ArtistCount", "ProgramCount", "TrailerCount", "SongCount", "AlbumCount", "MusicVideoCount", "BoxSetCount", "BookCount", "ItemCount"}) o.p(k, 0);
        return o.o;
    }

    // ---------- sites ----------

    /** Every Java site of the loaded config (cfjs v1 serves Java sites only), config order. */
    private static List<SiteInfo> served() {
        List<SiteInfo> out = new ArrayList<>();
        for (SiteInfo s : Engine.get().sites()) if (s.supported()) out.add(s);
        return out;
    }

    /** The site chosen in the app, else the first Java site (TVBox's default home is the first site). */
    static String homeKey() {
        if (!Engine.get().loaded()) return "";
        String chosen = Prefs.jellyfinHome();
        SiteInfo site = Engine.get().site(chosen);
        if (site != null && site.supported()) return chosen;
        List<SiteInfo> list = served();
        return list.isEmpty() ? "" : list.get(0).key;
    }

    private static final Pattern DECOR = Pattern.compile("[^\\p{L}\\p{N}┃|·\\- ]");

    /** Drops emoji / decoration so SenPlayer titles stay readable: "💥追剧┃秒播💥" -> "追剧┃秒播". */
    static String shortName(String key) {
        SiteInfo site = Engine.get().site(key);
        String n = site == null || site.name.isEmpty() ? key : site.name;
        String out = DECOR.matcher(n).replaceAll("").trim();
        return out.isEmpty() ? key : out;
    }

    private static String serverName() {
        String home = homeKey();
        return home.isEmpty() ? "Spider 服务" : shortName(home);
    }

    // ---------- entry ----------

    static Response handle(IHTTPSession session, String rawPath) {
        long started = System.currentTimeMillis();
        checkGeneration();
        Map<String, String> q = new HashMap<>();
        for (Map.Entry<String, List<String>> e : session.getParameters().entrySet()) {
            q.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().isEmpty() ? "" : e.getValue().get(0));
        }
        String body = "";
        if (session.getMethod() == NanoHTTPD.Method.POST || session.getMethod() == NanoHTTPD.Method.PUT) {
            Map<String, String> files = new HashMap<>();
            try {
                session.parseBody(files);
            } catch (Exception ignored) {
            }
            String post = files.get("postData");
            body = post == null ? "" : post;
        }
        String query = session.getQueryParameterString();
        BridgeLog.i("[JF] " + session.getMethod() + " " + rawPath + (query == null || query.isEmpty() ? "" : "?" + cut(query, 200)) + (body.isEmpty() ? "" : " body=" + cut(body, 200)));
        String path = rawPath.replaceFirst("(?i)^/(emby|jellyfin)(?=/|$)", "");
        List<String> seg = new ArrayList<>();
        for (String s : path.split("/")) if (!s.isEmpty()) seg.add(s);
        List<String> low = new ArrayList<>();
        for (String s : seg) low.add(s.toLowerCase(Locale.ROOT));
        Response resp;
        try {
            resp = route(session, low, seg, q, body);
        } catch (Throwable t) {
            BridgeLog.w("[JF] " + rawPath + " failed: " + t);
            resp = text(Response.Status.INTERNAL_ERROR, String.valueOf(t));
        }
        long ms = System.currentTimeMillis() - started;
        if (ms > 5000 || resp.getStatus().getRequestStatus() >= 400) BridgeLog.w("[JF] -> " + resp.getStatus().getRequestStatus() + " in " + ms + " ms " + rawPath);
        return resp;
    }

    private static String at(List<String> low, int i) {
        return i < low.size() ? low.get(i) : null;
    }

    private static boolean is(List<String> low, int i, String value) {
        return value.equals(at(low, i));
    }

    private static Response route(IHTTPSession s, List<String> low, List<String> seg, Map<String, String> q, String body) {
        int n = low.size();
        if (n == 0) return json(systemInfo(false));
        if (is(low, 0, "system") && is(low, 1, "info") && is(low, 2, "public")) return json(systemInfo(false));
        if (is(low, 0, "system") && is(low, 1, "info")) return json(systemInfo(true));
        if (is(low, 0, "system") && is(low, 1, "ping")) return text(Response.Status.OK, "\"" + serverName() + "\"");
        if (is(low, 0, "users") && is(low, 1, "authenticatebyname")) return json(authResult());
        if (is(low, 0, "users") && is(low, 1, "public") && n == 2) return json(new JsonArray());
        if (is(low, 0, "users") && n == 2) return json(user());
        if (is(low, 0, "users") && is(low, 2, "views")) return json(views());
        if (is(low, 0, "userviews")) return json(views());
        if (is(low, 0, "users") && is(low, 2, "groupingoptions")) return json(new JsonArray());
        if (is(low, 0, "users") && is(low, 2, "items") && is(low, 3, "resume")) return json(resume(q));
        if (is(low, 0, "useritems") && is(low, 1, "resume")) return json(resume(q));
        if (is(low, 0, "users") && is(low, 2, "items") && n == 5 && is(low, 4, "userdata")) return userDataRoute(s, seg.get(3), body);
        if (is(low, 0, "useritems") && n == 3 && is(low, 2, "userdata")) return userDataRoute(s, seg.get(1), body);
        if (is(low, 0, "users") && is(low, 2, "favoriteitems") && n >= 4) return favoriteRoute(s, seg.get(3));
        if (is(low, 0, "userfavoriteitems") && n >= 2) return favoriteRoute(s, seg.get(1));
        if (is(low, 0, "users") && is(low, 2, "playeditems") && n >= 4) return playedRoute(s, seg.get(3));
        if (is(low, 0, "userplayeditems") && n >= 2) return playedRoute(s, seg.get(1));
        if (is(low, 0, "users") && is(low, 2, "playingitems") && n >= 4) return playingItemRoute(s, seg.get(3), at(low, 4), q);
        if (is(low, 0, "playingitems") && n >= 2) return playingItemRoute(s, seg.get(1), at(low, 2), q);
        if (is(low, 0, "sessions") && is(low, 1, "playing")) return sessionPlaying(at(low, 2), body);
        if (is(low, 0, "users") && is(low, 2, "items") && is(low, 3, "latest")) return json(latest(q));
        if (is(low, 0, "items") && is(low, 1, "latest")) return json(latest(q));
        if (is(low, 0, "users") && is(low, 2, "items") && n == 4) return item(seg.get(3));
        if (is(low, 0, "users") && is(low, 2, "items") && n == 3) return json(items(q));
        if (is(low, 0, "items") && n == 1) return json(items(q));
        if (is(low, 0, "items") && is(low, 1, "counts")) return json(itemCounts());
        if (is(low, 0, "items") && n == 2 && low.get(1).length() == 32) return item(seg.get(1));
        if (is(low, 0, "items") && is(low, 2, "playbackinfo")) return json(playbackInfo(seg.get(1)));
        if (is(low, 0, "items") && is(low, 2, "images")) return image(seg.get(1));
        if (is(low, 0, "items") && (is(low, 2, "similar") || is(low, 2, "specialfeatures") || is(low, 2, "localtrailers") || is(low, 2, "thememedia"))) return json(queryResult(new JsonArray(), 0));
        if (is(low, 0, "shows") && is(low, 1, "nextup")) return json(nextUp(q));
        if (is(low, 0, "shows") && is(low, 2, "seasons")) return json(seasons(seg.get(1)));
        if (is(low, 0, "shows") && is(low, 2, "episodes")) return json(episodes(seg.get(1), q.get("seasonid")));
        if (is(low, 0, "search") && is(low, 1, "hints")) return json(searchHints(q));
        if ((is(low, 0, "videos") || is(low, 0, "audio")) && n >= 3) return stream(s, seg.get(1));
        if (is(low, 0, "genres") || is(low, 0, "studios") || is(low, 0, "persons") || is(low, 0, "artists")) return json(queryResult(new JsonArray(), 0));
        if (is(low, 0, "sessions")) return noContent();
        if (is(low, 0, "branding") && is(low, 1, "configuration")) return json(new O().p("LoginDisclaimer", "").p("CustomCss", "").p("SplashscreenEnabled", false).o);
        if (is(low, 0, "branding")) return text(Response.Status.OK, "");
        if (is(low, 0, "quickconnect") && is(low, 1, "enabled")) return text(Response.Status.OK, "false", "application/json; charset=utf-8");
        BridgeLog.w("JF UNHANDLED " + s.getMethod() + " /" + String.join("/", seg));
        return text(Response.Status.NOT_FOUND, "not implemented");
    }

    // ---------- small JSON builder (Gson, no checked exceptions) ----------

    static final class O {
        final JsonObject o = new JsonObject();

        O p(String k, String v) {
            o.addProperty(k, v);
            return this;
        }

        O p(String k, Number v) {
            o.addProperty(k, v);
            return this;
        }

        O p(String k, Boolean v) {
            o.addProperty(k, v);
            return this;
        }

        O p(String k, JsonElement v) {
            o.add(k, v);
            return this;
        }
    }

    // ---------- system / auth ----------

    private static JsonObject systemInfo(boolean full) {
        O o = new O().p("LocalAddress", JellyfinServer.BASE).p("ServerName", serverName()).p("Version", VERSION)
                .p("ProductName", "Jellyfin Server").p("OperatingSystem", "Android").p("Id", SERVER_ID).p("StartupWizardCompleted", true);
        if (full) o.p("OperatingSystemDisplayName", "Android").p("HasPendingRestart", false).p("IsShuttingDown", false);
        return o.o;
    }

    private static JsonObject user() {
        JsonObject config = new O().p("PlayDefaultAudioTrack", true).p("SubtitleLanguagePreference", "").p("DisplayMissingEpisodes", false)
                .p("GroupedFolders", new JsonArray()).p("SubtitleMode", "Default").p("DisplayCollectionsView", false).p("EnableLocalPassword", false)
                .p("OrderedViews", new JsonArray()).p("LatestItemsExcludes", new JsonArray()).p("MyMediaExcludes", new JsonArray())
                .p("HidePlayedInLatest", false).p("RememberAudioSelections", true).p("RememberSubtitleSelections", true).p("EnableNextEpisodeAutoPlay", true).o;
        JsonObject policy = new O().p("IsAdministrator", false).p("IsHidden", false).p("IsDisabled", false).p("EnableMediaPlayback", true)
                .p("EnableAudioPlaybackTranscoding", false).p("EnableVideoPlaybackTranscoding", false).p("EnablePlaybackRemuxing", false)
                .p("EnableContentDownloading", false).p("EnableAllFolders", true)
                .p("AuthenticationProviderId", "Jellyfin.Server.Implementations.Users.DefaultAuthenticationProvider")
                .p("PasswordResetProviderId", "Jellyfin.Server.Implementations.Users.DefaultPasswordResetProvider").o;
        return new O().p("Name", "cfjs").p("ServerId", SERVER_ID).p("ServerName", serverName()).p("Id", USER_ID)
                .p("HasPassword", false).p("HasConfiguredPassword", false).p("HasConfiguredEasyPassword", false).p("EnableAutoLogin", false)
                .p("Configuration", config).p("Policy", policy).o;
    }

    /** Any user name, any (empty) password. */
    private static JsonObject authResult() {
        BridgeLog.i("[JF] login accepted (any user/password)");
        JsonObject session = new O().p("Id", md5("session")).p("UserId", USER_ID).p("UserName", "cfjs").p("ServerId", SERVER_ID)
                .p("IsActive", true).p("SupportsMediaControl", false).p("SupportsRemoteControl", false)
                .p("PlayableMediaTypes", new JsonArray()).p("SupportedCommands", new JsonArray()).o;
        return new O().p("User", user()).p("SessionInfo", session).p("AccessToken", TOKEN).p("ServerId", SERVER_ID).o;
    }

    /** UserData from the local store (收藏、进度、已看). */
    private static JsonObject userData(String id) {
        JfUserStore.Prog p = JfUserStore.progress(id);
        boolean played = p != null && p.played;
        O o = new O().p("PlaybackPositionTicks", p == null ? 0L : p.ticks).p("PlayCount", played ? 1 : 0)
                .p("IsFavorite", JfUserStore.isFavorite(id)).p("Played", played).p("Key", id).p("ItemId", id);
        if (p != null) {
            o.p("LastPlayedDate", isoDate(p.at));
            if (p.runtime > 0 && !p.played) o.p("PlayedPercentage", p.ticks * 100.0 / p.runtime);
        }
        return o.o;
    }

    private static String isoDate(long ms) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(ms));
    }

    // ---------- library ----------

    /** Libraries: 推荐, the home site's categories (like 影视仓's 首页), then one 全部站点 library. */
    private static JsonObject views() {
        JsonArray arr = new JsonArray();
        String home = homeKey();
        if (!home.isEmpty()) {
            Home h = homeOf(home);
            if (h != null && !h.list.isEmpty()) arr.add(folderDto(viewId(home, REC_TID), "推荐", "CollectionFolder", "tvshows", null));
            if (h != null) for (String[] c : h.classes) arr.add(folderDto(viewId(home, c[0]), c[1], "CollectionFolder", "tvshows", null));
        }
        arr.add(folderDto(sitesRootId(), "全部站点（" + served().size() + "）", "CollectionFolder", "folders", null));
        return queryResult(arr, 0);
    }

    private static JsonObject folderDto(String id, String name, String type, String collectionType, String parentId) {
        O o = new O().p("Name", name).p("ServerId", SERVER_ID).p("Id", id).p("Type", type);
        if (collectionType != null) o.p("CollectionType", collectionType);
        if (parentId != null) o.p("ParentId", parentId);
        return o.p("IsFolder", true).p("ImageTags", new JsonObject()).p("BackdropImageTags", new JsonArray())
                .p("LocationType", "FileSystem").p("UserData", userData(id)).o;
    }

    private static JsonArray siteFolders() {
        JsonArray arr = new JsonArray();
        String home = homeKey();
        for (SiteInfo s : served()) arr.add(folderDto(siteFolderId(s.key), (s.key.equals(home) ? "★ " : "") + shortName(s.key), "Folder", null, sitesRootId()));
        return arr;
    }

    private static JsonArray classFolders(String site) {
        JsonArray arr = new JsonArray();
        Home h = homeOf(site);
        if (h == null) return arr;
        if (!h.list.isEmpty()) arr.add(folderDto(viewId(site, REC_TID), "推荐", "Folder", null, siteFolderId(site)));
        for (String[] c : h.classes) arr.add(folderDto(viewId(site, c[0]), c[1], "Folder", null, siteFolderId(site)));
        return arr;
    }

    private static int intParam(Map<String, String> q, String key, int def) {
        try {
            String v = q.get(key);
            return v == null || v.isEmpty() ? def : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int limitOf(Map<String, String> q, int def) {
        int v = intParam(q, "limit", -1);
        return v > 0 ? v : def;
    }

    private static JsonArray latest(Map<String, String> q) {
        List<String> parent = lookup(q.get("parentid"));
        String site;
        String tid;
        if ("view".equals(kind(parent))) {
            site = parent.get(0);
            tid = parent.get(2);
        } else if (parent != null) {
            return new JsonArray();
        } else {
            site = homeKey();
            Home h = site.isEmpty() ? null : homeOf(site);
            if (h == null || h.classes.isEmpty()) return new JsonArray();
            tid = h.classes.get(0)[0];
        }
        int limit = limitOf(q, 16);
        List<Vod> list = REC_TID.equals(tid) ? recommend(site) : category(site, tid, 1).list;
        JsonArray arr = new JsonArray();
        for (int i = 0; i < list.size() && i < limit; i++) arr.add(seriesDto(list.get(i), null));
        return arr;
    }

    private static JsonObject items(Map<String, String> q) {
        int start = Math.max(0, intParam(q, "startindex", 0));
        String term = q.getOrDefault("searchterm", "");
        if (!term.trim().isEmpty()) {
            String types = q.getOrDefault("includeitemtypes", "").toLowerCase(Locale.ROOT);
            // Everything we return is a Series; a search for only People/Episodes/etc gets nothing instead of duplicates.
            if (!types.isEmpty() && !types.contains("series")) return queryResult(new JsonArray(), 0);
            JsonArray arr = new JsonArray();
            for (List<Vod> g : searchEntries(term)) arr.add(entryDto(g));
            return queryResult(arr, 0);
        }
        if (q.getOrDefault("filters", "").toLowerCase(Locale.ROOT).contains("isfavorite") || "true".equals(q.get("isfavorite"))) return favoritesResult(q);
        String idList = q.get("ids");
        if (idList != null && !idList.trim().isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String id : idList.split(",")) {
                JsonObject dto = dtoFor(id.trim());
                if (dto != null) arr.add(dto);
            }
            return queryResult(arr, 0);
        }
        String parentId = q.getOrDefault("parentid", "");
        List<String> parent = lookup(parentId);
        switch (kind(parent)) {
            case "sites":
                return queryResult(siteFolders(), 0);
            case "site":
                return queryResult(classFolders(parent.get(0)), 0);
            case "view": {
                int pageSize = intParam(q, "limit", -1);
                List<Vod> list;
                int total;
                if (REC_TID.equals(parent.get(2))) {
                    List<Vod> all = recommend(parent.get(0));
                    int end = pageSize > 0 ? Math.min(all.size(), start + pageSize) : all.size();
                    list = start < end ? all.subList(start, end) : Collections.emptyList();
                    total = all.size();
                } else {
                    Page w = categoryWindow(parent.get(0), parent.get(2), start, pageSize > 0 ? pageSize : null);
                    list = w.list;
                    total = w.pageCount;
                }
                JsonArray arr = new JsonArray();
                for (Vod v : list) arr.add(seriesDto(v, null));
                return queryResult(arr, start, total);
            }
            case "series":
            case "group":
                return seasons(parentId);
            case "season":
                return episodes(seriesId(parent.get(0), parent.get(2)), parentId);
            default:
                BridgeLog.w("[JF] /Items without known ParentId/SearchTerm/Ids -> empty (params=" + q.keySet() + ")");
                return queryResult(new JsonArray(), 0);
        }
    }

    private static Response item(String id) {
        JsonObject dto = dtoFor(id);
        return dto == null ? text(Response.Status.NOT_FOUND, "unknown id " + id) : json(dto);
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static JsonObject dtoFor(String id) {
        List<String> key = lookup(id);
        if (key == null) return null;
        switch (kind(key)) {
            case "sites":
                return folderDto(sitesRootId(), "全部站点", "CollectionFolder", "folders", null);
            case "site":
                return folderDto(siteFolderId(key.get(0)), shortName(key.get(0)), "Folder", null, sitesRootId());
            case "view": {
                String site = key.get(0);
                String tid = key.get(2);
                String name = tid;
                if (REC_TID.equals(tid)) name = "推荐";
                else {
                    Home h = HOMES.get(site);
                    if (h != null) for (String[] c : h.classes) if (c[0].equals(tid)) name = c[1];
                }
                if (site.equals(homeKey())) return folderDto(viewId(site, tid), name, "CollectionFolder", "tvshows", null);
                return folderDto(viewId(site, tid), name, "Folder", null, siteFolderId(site));
            }
            case "series": {
                String nid = norm(id);
                List<Vod> members = groupMembers(nid, key);
                if (members != null) return groupDto(nid, members);
                Detail d = detail(key.get(0), key.get(2), false);
                if (d != null) return seriesDto(d.vod, d);
                Vod v = VODS.get(key.get(0) + "|" + key.get(2));
                return v == null ? null : seriesDto(v, null);
            }
            case "group": {
                String nid = norm(id);
                return groupDto(nid, groupMembers(nid, key));
            }
            case "season": {
                Detail d = detail(key.get(0), key.get(2), false);
                return d == null ? null : seasonDto(d, num(key.get(3)), num(key.get(3)) + 1);
            }
            case "episode": {
                Detail d = detail(key.get(0), key.get(2), false);
                return d == null ? null : episodeDto(d, num(key.get(3)), num(key.get(4)), true);
            }
            default:
                return null;
        }
    }

    private static JsonObject seasons(String seriesIdStr) {
        String nid = norm(seriesIdStr);
        List<String> key = lookup(nid);
        JsonArray arr = new JsonArray();
        if (key == null) return queryResult(arr, 0);
        List<Vod> members = groupMembers(nid, key);
        if (members != null) {
            // One season per (site, line): 「站名 · 线路（备注）」, numbered across all sites.
            int n = 0;
            for (Detail d : groupDetails(nid, members)) for (int f = 0; f < d.flags.size(); f++) arr.add(seasonDto(d, f, ++n));
            return queryResult(arr, 0);
        }
        if (!"series".equals(kind(key))) return queryResult(arr, 0);
        Detail d = detail(key.get(0), key.get(2), false);
        if (d == null) return queryResult(arr, 0);
        for (int f = 0; f < d.flags.size(); f++) arr.add(seasonDto(d, f, f + 1));
        return queryResult(arr, 0);
    }

    private static JsonObject episodes(String seriesIdStr, String seasonIdStr) {
        JsonArray arr = new JsonArray();
        List<String> sk = lookup(seasonIdStr);
        if ("season".equals(kind(sk))) {
            // The season id names its own site and vod, so this works for merged entries too.
            Detail d = detail(sk.get(0), sk.get(2), false);
            if (d == null) return queryResult(arr, 0);
            int f = num(sk.get(3));
            for (int e = 0; e < d.count(f); e++) arr.add(episodeDto(d, f, e, false));
            return queryResult(arr, 0);
        }
        String nid = norm(seriesIdStr);
        List<String> key = lookup(nid);
        if (key == null) return queryResult(arr, 0);
        List<Vod> members = groupMembers(nid, key);
        List<Detail> list = new ArrayList<>();
        if (members != null) list.addAll(groupDetails(nid, members));
        else if ("series".equals(kind(key))) {
            Detail d = detail(key.get(0), key.get(2), false);
            if (d != null) list.add(d);
        }
        for (Detail d : list) for (int f = 0; f < d.flags.size(); f++) for (int e = 0; e < d.count(f); e++) arr.add(episodeDto(d, f, e, false));
        return queryResult(arr, 0);
    }

    private static JsonObject searchHints(Map<String, String> q) {
        String term = q.getOrDefault("searchterm", "");
        JsonArray arr = new JsonArray();
        if (!term.trim().isEmpty()) for (List<Vod> g : searchEntries(term)) {
            Vod v = g.get(0);
            String id = g.size() == 1 ? seriesId(v) : groupId(g);
            if (g.size() > 1) remember(id, entryName(g), g);
            O o = new O().p("ItemId", id).p("Id", id).p("Name", entryName(g)).p("Type", "Series").p("IsFolder", true).p("MediaType", "Unknown");
            if (!v.pic.isEmpty()) o.p("PrimaryImageTag", md5(v.pic).substring(0, 16));
            Integer year = year(v.year);
            if (year != null) o.p("ProductionYear", year);
            arr.add(o.o);
        }
        return new O().p("SearchHints", arr).p("TotalRecordCount", arr.size()).o;
    }

    // ---------- 推荐 / 评分 ----------

    /** The site's 推荐 list (homeContent list, else homeVideoContent; SpiderBridge.home merges them). */
    private static List<Vod> recommend(String site) {
        Home h = homeOf(site);
        return h == null ? Collections.emptyList() : h.list;
    }

    private static final Pattern[] RATING = {
            Pattern.compile("(?:评分|豆瓣|豆瓣评分|IMDb|imdb)\\s*[:：]?\\s*(10(?:\\.0)?|\\d(?:\\.\\d)?)"),
            Pattern.compile("^\\s*(10(?:\\.0)?|\\d(?:\\.\\d)?)\\s*分\\s*$"),
            Pattern.compile("^\\s*(10\\.0|\\d\\.\\d)\\s*$"),
    };

    /** A rating only when vod_remarks really is one (「评分8.7」「8.7分」「8.7」); 「更新至10集」「HD」 give nothing. */
    private static Double ratingOf(String remarks) {
        if (remarks == null || remarks.trim().isEmpty()) return null;
        for (Pattern p : RATING) {
            Matcher m = p.matcher(remarks);
            if (m.find()) {
                try {
                    double v = Double.parseDouble(m.group(1));
                    return v >= 0 && v <= 10 ? v : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Integer year(String s) {
        try {
            return s == null || s.trim().isEmpty() ? null : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- 收藏 / 进度 ----------

    /** A small picture of an item, kept with its favorite/progress so lists can be drawn without calling the site. */
    private static JfUserStore.Snap snapOf(String id) {
        String nid = norm(id);
        List<String> key = lookup(nid);
        if (key == null) return null;
        switch (kind(key)) {
            case "series": {
                List<Vod> members = groupMembers(nid, key);
                if (members != null && !members.isEmpty()) return groupSnap(nid, key, members);
                String k = key.get(0) + "|" + key.get(2);
                Detail cached = DETAILS.get(k);
                Vod v = cached != null ? cached.vod : VODS.get(k);
                if (v == null) {
                    Detail d = detail(key.get(0), key.get(2), false);
                    if (d == null) return null;
                    v = d.vod;
                }
                String pic = knownPic(v.site, v.id);
                return new JfUserStore.Snap(nid, "series", v.name, pic != null ? pic : v.pic, v.year);
            }
            case "group": {
                List<Vod> g = groupMembers(nid, key);
                return g == null || g.isEmpty() ? null : groupSnap(nid, key, g);
            }
            case "episode": {
                Detail d = detail(key.get(0), key.get(2), false);
                if (d == null) return null;
                int f = num(key.get(3));
                int e = num(key.get(4));
                String[] ep = d.episode(f, e);
                return new JfUserStore.Snap(nid, "episode", ep != null ? ep[0] : "第" + (e + 1) + "集", d.vod.pic, d.vod.year,
                        ownerId(d), ownerName(d), seasonId(d.vod.site, d.vod.id, f), seasonName(d, f), e + 1, f + 1);
            }
            case "season": {
                Detail d = detail(key.get(0), key.get(2), false);
                if (d == null) return null;
                return new JfUserStore.Snap(nid, "season", seasonName(d, num(key.get(3))), d.vod.pic, d.vod.year, ownerId(d), ownerName(d), "", "", 0, 0);
            }
            default:
                return null;
        }
    }

    private static JfUserStore.Snap groupSnap(String nid, List<String> key, List<Vod> g) {
        String[] owner = OWNERS.get(g.get(0).key());
        String name = owner != null ? owner[1] : "series".equals(kind(key)) ? g.get(0).name : entryName(g);
        String pic = "";
        String yr = "";
        for (Vod v : g) if (pic.isEmpty() && v.pic.startsWith("http")) pic = v.pic;
        for (Vod v : g) if (yr.isEmpty() && !v.year.isEmpty()) yr = v.year;
        return new JfUserStore.Snap(nid, "group", name, pic, yr);
    }

    private static JsonObject snapSeriesDto(JfUserStore.Snap s) {
        O o = new O().p("Name", s.name).p("ServerId", SERVER_ID).p("Id", s.id).p("Type", "Series").p("IsFolder", true)
                .p("MediaType", "Unknown").p("LocationType", "Remote").p("UserData", userData(s.id)).p("ImageTags", imageTags(s.pic))
                .p("BackdropImageTags", new JsonArray()).p("PrimaryImageAspectRatio", POSTER_RATIO);
        Integer yr = year(s.year);
        if (yr != null) o.p("ProductionYear", yr);
        return o.o;
    }

    private static JsonObject snapEpisodeDto(JfUserStore.Snap s) {
        O o = new O().p("Name", s.name).p("ServerId", SERVER_ID).p("Id", s.id).p("Type", "Episode").p("IsFolder", false)
                .p("MediaType", "Video").p("VideoType", "VideoFile").p("LocationType", "Remote").p("IndexNumber", s.index)
                .p("ParentIndexNumber", s.parentIndex).p("SeasonId", s.seasonId).p("SeasonName", s.seasonName).p("SeriesId", s.seriesId)
                .p("SeriesName", s.seriesName).p("ParentId", s.seasonId).p("UserData", userData(s.id)).p("ImageTags", imageTags(s.pic));
        if (!s.pic.isEmpty()) o.p("SeriesPrimaryImageTag", md5(s.pic).substring(0, 16));
        JfUserStore.Prog p = JfUserStore.progress(s.id);
        if (p != null && p.runtime > 0) o.p("RunTimeTicks", p.runtime);
        return o.o;
    }

    private static JsonObject snapDto(JfUserStore.Snap s) {
        return "episode".equals(s.kind) ? snapEpisodeDto(s) : snapSeriesDto(s);
    }

    /** 继续观看: unfinished episodes, newest first, one per series. */
    private static JsonObject resume(Map<String, String> q) {
        JsonArray arr = new JsonArray();
        List<JfUserStore.Prog> list = JfUserStore.resume();
        int limit = limitOf(q, 20);
        for (int i = 0; i < list.size() && i < limit; i++) arr.add(snapEpisodeDto(list.get(i).snap));
        return queryResult(arr, 0);
    }

    /** 下一集: after a finished episode, the next one on the same line; loads at most 4 uncached series within 8 s. */
    private static JsonObject nextUp(Map<String, String> q) {
        String only = norm(q.get("seriesid"));
        int limit = limitOf(q, 20);
        List<List<String>> keys = new ArrayList<>();
        for (JfUserStore.Prog p : JfUserStore.lastWatched()) {
            if (!only.isEmpty() && !only.equals(p.snap.seriesId)) continue;
            if (keys.size() >= limit) break;
            List<String> k = lookup(p.snap.id);
            if ("episode".equals(kind(k))) keys.add(k);
        }
        List<String> need = new ArrayList<>();
        for (List<String> k : keys) {
            String t = k.get(0) + "\u0002" + k.get(2);
            if (DETAILS.get(k.get(0) + "|" + k.get(2)) == null && !need.contains(t) && need.size() < 4) need.add(t);
        }
        if (!need.isEmpty()) fanOut(need, 8, t -> detail(t.substring(0, t.indexOf('\u0002')), t.substring(t.indexOf('\u0002') + 1), true));
        JsonArray arr = new JsonArray();
        for (List<String> k : keys) {
            Detail d = DETAILS.get(k.get(0) + "|" + k.get(2));
            if (d == null) continue;
            int f = num(k.get(3));
            int e = num(k.get(4)) + 1;
            if (e < d.count(f)) {
                JfUserStore.Prog p = JfUserStore.progress(episodeId(d.vod.site, d.vod.id, f, e));
                if (p == null || !p.played) arr.add(episodeDto(d, f, e, false));
            }
        }
        return queryResult(arr, 0);
    }

    private static JsonObject favoritesResult(Map<String, String> q) {
        String types = q.getOrDefault("includeitemtypes", "").toLowerCase(Locale.ROOT);
        List<JfUserStore.Snap> all = new ArrayList<>();
        for (JfUserStore.Fav f : JfUserStore.favorites()) {
            String k = f.snap.kind;
            boolean ok = types.isEmpty() || ("episode".equals(k) ? types.contains("episode") : "season".equals(k) ? types.contains("season") : types.contains("series"));
            if (ok) all.add(f.snap);
        }
        int start = Math.max(0, intParam(q, "startindex", 0));
        int limit = limitOf(q, 200);
        JsonArray arr = new JsonArray();
        for (int i = start; i < all.size() && i < start + limit; i++) arr.add(snapDto(all.get(i)));
        return queryResult(arr, start, all.size());
    }

    private static Response favoriteRoute(IHTTPSession s, String id) {
        String nid = norm(id);
        if (s.getMethod() != NanoHTTPD.Method.DELETE) {
            JfUserStore.Snap snap = snapOf(nid);
            if (snap == null) return text(Response.Status.NOT_FOUND, "unknown id " + id);
            JfUserStore.setFavorite(snap, true);
        } else {
            JfUserStore.setFavorite(new JfUserStore.Snap(nid, "", "", "", ""), false);
        }
        return json(userData(nid));
    }

    private static Response playedRoute(IHTTPSession s, String id) {
        String nid = norm(id);
        JfUserStore.Snap snap = snapOf(nid);
        if (snap == null) return text(Response.Status.NOT_FOUND, "unknown id " + id);
        boolean delete = s.getMethod() == NanoHTTPD.Method.DELETE;
        if ("episode".equals(snap.kind)) JfUserStore.report(snap, null, null, !delete);
        BridgeLog.i("[JF] USER " + (delete ? "标为未看 " : "标为已看 ") + snap.kind + " " + snap.name);
        return json(userData(nid));
    }

    /** Legacy /Users/{u}/PlayingItems/{id}[/Progress]?PositionTicks=… */
    private static Response playingItemRoute(IHTTPSession s, String id, String sub, Map<String, String> q) {
        Long ticks = null;
        try {
            String v = q.get("positionticks");
            if (v != null && !v.isEmpty()) ticks = Long.parseLong(v);
        } catch (NumberFormatException ignored) {
        }
        String what = s.getMethod() == NanoHTTPD.Method.DELETE || "stopped".equals(sub) ? "停止" : "progress".equals(sub) ? "进度" : "开始";
        recordProgress(id, ticks, null, what);
        return noContent();
    }

    /** POST /Sessions/Playing, /Sessions/Playing/Progress, /Sessions/Playing/Stopped with PlaybackStartInfo-style JSON. */
    private static Response sessionPlaying(String sub, String body) {
        JsonObject o = parseObject(body);
        String id = str(o, "ItemId");
        if (id.isEmpty()) {
            BridgeLog.w("[JF] USER Sessions/Playing/" + (sub == null ? "" : sub) + " 没有 ItemId");
            return noContent();
        }
        Long ticks = o.has("PositionTicks") ? longOf(o, "PositionTicks") : null;
        Long runtime = null;
        JsonElement item = o.get("Item");
        if (item != null && item.isJsonObject()) {
            long v = longOf(item.getAsJsonObject(), "RunTimeTicks");
            if (v > 0) runtime = v;
        }
        if (runtime == null) {
            long v = longOf(o, "RunTimeTicks");
            if (v > 0) runtime = v;
        }
        String what = sub == null ? "开始" : "progress".equals(sub) ? "进度" : "stopped".equals(sub) ? "停止" : sub;
        recordProgress(id, ticks, runtime, what);
        return noContent();
    }

    private static void recordProgress(String id, Long ticks, Long runtime, String what) {
        String nid = norm(id);
        // Episodes only; the detail is already cached while it plays, so this rarely calls the site.
        if (!"episode".equals(kind(lookup(nid)))) return;
        JfUserStore.Snap snap = snapOf(nid);
        if (snap == null) return;
        JfUserStore.report(snap, ticks, runtime, null);
        long now = System.currentTimeMillis();
        Long last = LAST_PROGRESS_LOG.get(nid);
        if (!"进度".equals(what) || last == null || now - last > 60_000) {
            LAST_PROGRESS_LOG.put(nid, now);
            BridgeLog.i("[JF] USER " + what + " " + snap.seriesName + " " + snap.name + " 位置 " + (ticks == null ? 0 : ticks / 10_000_000) + "s" + (runtime == null ? "" : " / " + runtime / 10_000_000 + "s"));
        }
    }

    /** GET or POST /Users/{u}/Items/{id}/UserData (UpdateUserItemDataDto). */
    private static Response userDataRoute(IHTTPSession s, String id, String body) {
        String nid = norm(id);
        if (s.getMethod() == NanoHTTPD.Method.POST) {
            JsonObject o = parseObject(body);
            if (o.has("IsFavorite")) {
                boolean on = bool(o, "IsFavorite");
                if (on) {
                    JfUserStore.Snap snap = snapOf(nid);
                    if (snap != null) JfUserStore.setFavorite(snap, true);
                } else {
                    JfUserStore.setFavorite(new JfUserStore.Snap(nid, "", "", "", ""), false);
                }
            }
            if (o.has("Played") || o.has("PlaybackPositionTicks")) {
                JfUserStore.Snap snap = snapOf(nid);
                if (snap != null && "episode".equals(snap.kind)) {
                    JfUserStore.report(snap, o.has("PlaybackPositionTicks") ? longOf(o, "PlaybackPositionTicks") : null, null, o.has("Played") ? bool(o, "Played") : null);
                }
            }
        }
        return json(userData(nid));
    }

    // ---------- DTOs ----------

    private static String[] owner(Detail d) {
        return OWNERS.get(d.vod.key());
    }

    private static String ownerId(Detail d) {
        String[] o = owner(d);
        return o != null ? o[0] : seriesId(d.vod);
    }

    private static String ownerName(Detail d) {
        String[] o = owner(d);
        return o != null ? o[1] : d.vod.name;
    }

    private static String seasonName(Detail d, int f) {
        String line = f >= 0 && f < d.flags.size() ? d.flags.get(f) : "线路" + (f + 1);
        if (owner(d) == null) return line;
        String rem = d.vod.remarks.trim().isEmpty() ? "" : "（" + d.vod.remarks + "）";
        return shortName(d.vod.site) + " · " + line + rem;
    }

    private static JsonObject seriesDto(Vod v, Detail d) {
        String id = seriesId(v);
        String pic = knownPic(v.site, v.id);
        O o = new O().p("Name", v.name).p("ServerId", SERVER_ID).p("Id", id).p("Type", "Series").p("IsFolder", true)
                .p("MediaType", "Unknown").p("LocationType", "Remote").p("UserData", userData(id))
                .p("ImageTags", imageTags(pic != null ? pic : v.pic)).p("BackdropImageTags", new JsonArray())
                .p("PrimaryImageAspectRatio", POSTER_RATIO);
        Double rating = ratingOf(v.remarks);
        if (rating != null) o.p("CommunityRating", rating);
        Integer yr = year(v.year);
        if (yr != null) o.p("ProductionYear", yr);
        if (d != null) o.p("Overview", d.content).p("ChildCount", d.flags.size()).p("People", people(d));
        return o.o;
    }

    private static JsonObject seasonDto(Detail d, int f, int index) {
        String sid = seasonId(d.vod.site, d.vod.id, f);
        O o = new O().p("Name", seasonName(d, f)).p("ServerId", SERVER_ID).p("Id", sid).p("Type", "Season").p("IsFolder", true)
                .p("IndexNumber", index).p("SeriesId", ownerId(d)).p("SeriesName", ownerName(d)).p("ParentId", ownerId(d))
                .p("ChildCount", d.count(f)).p("LocationType", "Remote").p("UserData", userData(sid)).p("ImageTags", imageTags(d.vod.pic));
        if (!d.vod.pic.isEmpty()) o.p("SeriesPrimaryImageTag", md5(d.vod.pic).substring(0, 16));
        return o.o;
    }

    private static JsonObject episodeDto(Detail d, int f, int e, boolean withSources) {
        String id = episodeId(d.vod.site, d.vod.id, f, e);
        String sid = seasonId(d.vod.site, d.vod.id, f);
        String[] ep = d.episode(f, e);
        O o = new O().p("Name", ep != null ? ep[0] : "第" + (e + 1) + "集").p("ServerId", SERVER_ID).p("Id", id).p("Type", "Episode")
                .p("IsFolder", false).p("MediaType", "Video").p("VideoType", "VideoFile").p("LocationType", "Remote")
                .p("IndexNumber", e + 1).p("ParentIndexNumber", f + 1).p("SeasonId", sid).p("SeasonName", seasonName(d, f))
                .p("SeriesId", ownerId(d)).p("SeriesName", ownerName(d)).p("ParentId", sid).p("UserData", userData(id));
        JfUserStore.Prog p = JfUserStore.progress(id);
        if (p != null && p.runtime > 0) o.p("RunTimeTicks", p.runtime);
        o.p("ImageTags", imageTags(d.vod.pic));
        if (!d.vod.pic.isEmpty()) o.p("SeriesPrimaryImageTag", md5(d.vod.pic).substring(0, 16));
        if (withSources) {
            JsonArray sources = new JsonArray();
            sources.add(mediaSource(id, null, 0));
            o.p("MediaSources", sources);
        }
        return o.o;
    }

    private static final Pattern PEOPLE_SPLIT = Pattern.compile("[,，/ ]");

    private static JsonArray people(Detail d) {
        JsonArray arr = new JsonArray();
        int n = 0;
        for (String it : PEOPLE_SPLIT.split(d.director)) {
            if (it.trim().isEmpty() || n++ >= 5) continue;
            arr.add(new O().p("Name", it.trim()).p("Type", "Director").p("Id", md5("p|" + it)).o);
        }
        n = 0;
        for (String it : PEOPLE_SPLIT.split(d.actor)) {
            if (it.trim().isEmpty() || n++ >= 15) continue;
            arr.add(new O().p("Name", it.trim()).p("Type", "Actor").p("Id", md5("p|" + it)).o);
        }
        return arr;
    }

    /** Always advertise a Primary image: an empty list pic is filled from the detail when the image is asked for. */
    private static JsonObject imageTags(String pic) {
        return new O().p("Primary", md5(pic == null || pic.trim().isEmpty() ? "nopic" : pic).substring(0, 16)).o;
    }

    private static JsonObject queryResult(JsonArray items, int start) {
        return queryResult(items, start, start + items.size());
    }

    private static JsonObject queryResult(JsonArray items, int start, int total) {
        return new O().p("Items", items).p("TotalRecordCount", total).p("StartIndex", start).o;
    }

    // ---------- playback ----------

    /** One MediaSource per quality when the spider returns several urls, so the player's version menu can switch. */
    private static JsonObject mediaSource(String itemId, Resolved r, int index) {
        String[] q = r != null && index < r.qualities.size() ? r.qualities.get(index) : null;
        String container = q != null ? containerOf(q[1]) : "mp4";
        String msId = index == 0 ? itemId : qualityId(itemId, index);
        String name = q != null && !q[0].trim().isEmpty() ? q[0] : "Spider";
        // A spider proxy URL (already on 127.0.0.1:9978/proxy) is handed to SenPlayer as is.
        String path = q != null && LocalProxy.isProxyUrl(q[1]) ? q[1] : JellyfinServer.BASE + "/Videos/" + itemId + "/stream?static=true&MediaSourceId=" + msId;
        return new O().p("Protocol", "Http").p("Id", msId).p("Path", path)
                .p("Type", "Default").p("Container", container).p("Name", name).p("IsRemote", true).p("ReadAtNativeFramerate", false)
                .p("IgnoreDts", false).p("IgnoreIndex", false).p("GenPtsInput", false).p("SupportsTranscoding", false)
                .p("SupportsDirectStream", true).p("SupportsDirectPlay", true).p("IsInfiniteStream", false).p("RequiresOpening", false)
                .p("RequiresClosing", false).p("RequiresLooping", false).p("SupportsProbing", false).p("MediaStreams", new JsonArray())
                .p("MediaAttachments", new JsonArray()).p("Formats", new JsonArray()).p("RequiredHttpHeaders", new JsonObject()).o;
    }

    private static String qualityId(String itemId, int i) {
        String id = md5(itemId + "|q" + i);
        QUALITY_IDS.put(id, new Object[]{itemId, i});
        return id;
    }

    private static String containerOf(String url) {
        String p = url.split("\\?", 2)[0].toLowerCase(Locale.ROOT);
        if (p.endsWith(".m3u8")) return "hls";
        if (p.endsWith(".flv")) return "flv";
        if (p.endsWith(".mkv")) return "mkv";
        if (p.endsWith(".ts")) return "ts";
        return "mp4";
    }

    private static JsonObject playbackInfo(String id) {
        String nid = norm(id);
        Resolved r = resolve(nid);
        StringBuilder log = new StringBuilder();
        if (r != null) for (String[] q : r.qualities) log.append(q[0]).append('=').append(cut(q[1], 120)).append(' ');
        BridgeLog.i("[JF] PlaybackInfo " + nid + " -> " + (r == null ? "无地址" : log + "headers=" + r.headers.keySet()));
        JsonArray sources = new JsonArray();
        if (r != null) for (int i = 0; i < r.qualities.size(); i++) sources.add(mediaSource(nid, r, i));
        O o = new O().p("MediaSources", sources).p("PlaySessionId", md5("ps|" + nid + "|" + System.currentTimeMillis()));
        if (r == null) o.p("ErrorCode", "NoCompatibleStream");
        return o.o;
    }

    private static Resolved resolve(String id) {
        Resolved cached = RESOLVED.get(id);
        if (cached != null && System.currentTimeMillis() - cached.at < 10 * 60_000) return cached;
        List<String> key = lookup(id);
        if (!"episode".equals(kind(key))) {
            BridgeLog.w("[JF] resolve: " + id + " 不是剧集 id");
            return null;
        }
        String site = key.get(0);
        Detail d = detail(site, key.get(2), false);
        if (d == null) return null;
        int f = num(key.get(3));
        int e = num(key.get(4));
        String[] ep = d.episode(f, e);
        if (f >= d.flags.size() || ep == null) return null;
        String flag = d.flags.get(f);
        JsonObject p;
        try {
            p = bridge().play(site, flag, ep[1], PLAY_MS).value;
        } catch (BridgeException ex) {
            BridgeLog.w("[JF] playerContent " + site + " " + flag + " " + ex.code + ": " + ex.getMessage());
            return null;
        }
        BridgeLog.i("[JF] playerContent url=" + cut(str(p, "url"), 300) + " parse=" + intOf(p, "parse") + " jx=" + intOf(p, "jx") + " header=" + (p.get("header") != null && p.get("header").isJsonObject() ? p.get("header").getAsJsonObject().keySet() : "{}"));
        if (intOf(p, "parse") == 1 || intOf(p, "jx") == 1) {
            BridgeLog.w("[JF] 不兼容：parse/jx=1（需要网页嗅探/解析，v1 不支持）");
            return null;
        }
        if (!str(p, "playUrl").isEmpty()) {
            BridgeLog.w("[JF] 不兼容：playUrl 解析前缀（v1 不支持）");
            return null;
        }
        List<String[]> qualities = qualitiesOf(p);
        if (qualities.isEmpty()) {
            BridgeLog.w("[JF] 无可用 http 地址: " + cut(String.valueOf(p.get("urls")), 200));
            String driveName = NetDisk.driveOf(flag);
            if (!driveName.isEmpty()) NetDisk.w(driveName + " 线路 " + flag + " playerContent 没给地址（多半是没登录/Cookie 失效）raw=" + cut(String.valueOf(withoutHeader(p.get("raw"))), 400));
            return null;
        }
        String drive = NetDisk.driveOf(flag);
        if (!drive.isEmpty()) NetDisk.i(drive + " 线路 " + flag + " -> " + (LocalProxy.isProxyUrl(qualities.get(0)[1]) ? "本地代理 " : "直链 ") + cut(qualities.get(0)[1], 200) + " header=" + (p.get("header") == null ? "{}" : p.get("header").getAsJsonObject().keySet()));
        Map<String, String> headers = new LinkedHashMap<>();
        JsonElement h = p.get("header");
        if (h != null && h.isJsonObject()) for (Map.Entry<String, JsonElement> entry : h.getAsJsonObject().entrySet()) {
            JsonElement v = entry.getValue();
            headers.put(entry.getKey(), v.isJsonPrimitive() ? v.getAsString() : v.toString());
        }
        Resolved r = new Resolved(qualities, headers, System.currentTimeMillis());
        RESOLVED.put(id, r);
        return r;
    }

    /** PlayInfo's urls [{name,url}]; a single url holding a JSON array ["name1","url1",...] is split too. */
    private static List<String[]> qualitiesOf(JsonObject p) {
        List<String[]> raw = new ArrayList<>();
        JsonElement urls = p.get("urls");
        if (urls != null && urls.isJsonArray()) for (JsonElement el : urls.getAsJsonArray()) {
            if (el.isJsonObject()) raw.add(new String[]{str(el.getAsJsonObject(), "name"), str(el.getAsJsonObject(), "url")});
        }
        if (raw.size() == 1 && raw.get(0)[1].trim().startsWith("[")) {
            JsonElement parsed = null;
            try {
                parsed = Json.parse(raw.get(0)[1].trim());
            } catch (RuntimeException ignored) {
            }
            if (parsed != null && parsed.isJsonArray()) {
                raw.clear();
                JsonArray arr = parsed.getAsJsonArray();
                for (int i = 0; i + 1 < arr.size(); i += 2) {
                    JsonElement a = arr.get(i);
                    JsonElement b = arr.get(i + 1);
                    raw.add(new String[]{a.isJsonPrimitive() ? a.getAsString() : a.toString(), b.isJsonPrimitive() ? b.getAsString() : b.toString()});
                }
            }
        }
        List<String[]> out = new ArrayList<>();
        for (String[] q : raw) {
            String url = localize(q[1].trim());
            if (url.startsWith("http")) out.add(new String[]{q[0], url});
        }
        return out;
    }

    /** Spider result without its header (Cookie/Authorization must never reach the log). */
    static JsonElement withoutHeader(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) return raw;
        JsonObject copy = raw.getAsJsonObject().deepCopy();
        copy.remove("header");
        return copy;
    }

    private static final Pattern PROXY_HOST = Pattern.compile("^http://[^/:]+:" + JellyfinServer.PORT + "/proxy");

    /** proxy:// and the phone's own LAN address both mean this server's /proxy, reached on 127.0.0.1. */
    private static String localize(String url) {
        if (url.startsWith("proxy://")) return JellyfinServer.BASE + "/proxy?" + url.substring(8);
        Matcher m = PROXY_HOST.matcher(url);
        return m.find() ? JellyfinServer.BASE + "/proxy" + url.substring(m.end()) : url;
    }

    private static Response stream(IHTTPSession s, String id) {
        String ms = null;
        for (Map.Entry<String, List<String>> e : s.getParameters().entrySet()) {
            if (e.getKey().equalsIgnoreCase("MediaSourceId") && !e.getValue().isEmpty()) ms = e.getValue().get(0);
        }
        String itemId = norm(id);
        int qi = 0;
        Object[] q = ms == null ? null : QUALITY_IDS.get(norm(ms));
        if (q != null) {
            itemId = (String) q[0];
            qi = (Integer) q[1];
        }
        Resolved r = resolve(itemId);
        if (r == null) return text(Response.Status.NOT_FOUND, "no playable url");
        String url = (qi < r.qualities.size() ? r.qualities.get(qi) : r.qualities.get(0))[1];
        if (LocalProxy.isProxyUrl(url)) {
            // Spider local proxy (drive multi-thread download etc.): served right here with SenPlayer's Range,
            // never as a redirect to an "external" address.
            return LocalProxy.serveUrl(s, url, "[JF] stream " + itemId + " q" + qi);
        }
        boolean local = url.startsWith(JellyfinServer.BASE + "/");
        if (r.headers.isEmpty() || local) {
            BridgeLog.i("[JF] stream " + itemId + " q" + qi + " -> 302 " + cut(url, 200));
            return redirect(url);
        }
        return JfRelay.fetch(s, url, r.headers, "[JF] stream " + itemId + " q" + qi);
    }

    // ---------- images ----------

    /**
     * Search lists often leave vod_pic empty, use //host/... or append TVBox-style "@Referer=...@User-Agent=..."
     * headers, or sit behind hotlink protection. So an empty pic is taken from the detail (filled by a small
     * background queue, never on this thread), and the picture is fetched here with those headers.
     */
    private static Response image(String id) {
        String nid = norm(id);
        List<String> key = lookup(nid);
        if (key == null) return text(Response.Status.NOT_FOUND, "unknown id");
        String kind = kind(key);
        if ("series".equals(kind) && key.get(2).startsWith(MSEARCH)) {
            // An msearch item has its own poster from the list it came from; only search when it has none.
            String own = picOf(key.get(0), key.get(2));
            if (own != null) return fetchImage(own);
        }
        List<String[]> targets = new ArrayList<>();
        List<Vod> members = "group".equals(kind) || "series".equals(kind) ? groupMembers(nid, key) : null;
        if (members != null) for (Vod v : members) targets.add(new String[]{v.site, v.id});
        else if ("series".equals(kind) || "season".equals(kind) || "episode".equals(kind)) targets.add(new String[]{key.get(0), key.get(2)});
        else return text(Response.Status.NOT_FOUND, "no image");
        String pic = null;
        for (String[] t : targets) if ((pic = picOf(t[0], t[1])) != null) break;
        if (pic == null && !targets.isEmpty()) {
            String[] t = targets.get(0);
            PicBackfill.await(t[0], t[1], 5_000);
            pic = picOf(t[0], t[1]);
        }
        return pic == null ? text(Response.Status.NOT_FOUND, "no image") : fetchImage(pic);
    }

    /** The picture already known for a vod (list or cached detail), as stored. Never loads anything. */
    private static String knownPic(String site, String vodId) {
        String k = site + "|" + vodId;
        Vod v = VODS.get(k);
        if (v != null && !v.pic.trim().isEmpty()) return v.pic;
        Detail d = DETAILS.get(k);
        if (d != null && !d.vod.pic.trim().isEmpty()) return d.vod.pic;
        return null;
    }

    private static String picOf(String site, String vodId) {
        String p = knownPic(site, vodId);
        if (p == null) return null;
        p = p.trim();
        if (p.startsWith("//")) return "https:" + p;
        if (p.startsWith("http")) return p;
        return null;
    }

    private static boolean failedRecently(String k) {
        Long at = DETAIL_FAILED_AT.get(k);
        return at != null && System.currentTimeMillis() - at < DETAIL_FAIL_TTL;
    }

    /**
     * Fills missing posters from detailContent in the background: one at a time over all sites, newest request
     * first (what is on screen now), skipped while the site is serving a request, and capped at 60 queued items.
     */
    private static final class PicBackfill {
        private static final int MAX_QUEUE = 60;
        private static final LinkedBlockingDeque<String> QUEUE = new LinkedBlockingDeque<>();
        private static final ConcurrentHashMap<String, CountDownLatch> WAITERS = new ConcurrentHashMap<>();

        static {
            Thread worker = new Thread(PicBackfill::loop, "cfjs-jf-pic");
            worker.setDaemon(true);
            worker.start();
        }

        static void await(String site, String vodId, long ms) {
            String k = site + "|" + vodId;
            if (failedRecently(k)) return;
            CountDownLatch fresh = new CountDownLatch(1);
            CountDownLatch prev = WAITERS.putIfAbsent(k, fresh);
            CountDownLatch latch = prev != null ? prev : fresh;
            QUEUE.remove(k);
            QUEUE.offerFirst(k);
            while (QUEUE.size() > MAX_QUEUE) {
                String dropped = QUEUE.pollLast();
                if (dropped == null) break;
                CountDownLatch l = WAITERS.remove(dropped);
                if (l != null) l.countDown();
            }
            try {
                latch.await(ms, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static void loop() {
            while (true) {
                String k;
                try {
                    k = QUEUE.takeFirst();
                } catch (InterruptedException e) {
                    continue;
                }
                int bar = k.indexOf('|');
                String site = k.substring(0, bar);
                String vodId = k.substring(bar + 1);
                try {
                    // Let the site finish what the user is waiting for first.
                    int spins = 0;
                    while (bridge().busy(site) && spins++ < 40) Thread.sleep(250);
                    if (knownPic(site, vodId) == null && !failedRecently(k)) detail(site, vodId, true);
                } catch (Throwable t) {
                    BridgeLog.w("[JF] 补封面失败 " + k + ": " + t);
                } finally {
                    CountDownLatch l = WAITERS.remove(k);
                    if (l != null) l.countDown();
                }
            }
        }
    }

    private static final long IMG_DISK_MAX = 50L * 1024 * 1024;
    private static final LruCache<String, Object[]> IMG_MEM = new LruCache<String, Object[]>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Object[] value) {
            return ((byte[]) value[1]).length;
        }
    };

    private static Response imageResponse(String type, byte[] bytes) {
        Response r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, type, new ByteArrayInputStream(bytes), bytes.length);
        r.addHeader("Cache-Control", "public, max-age=86400");
        return r;
    }

    private static Object[] cachedImage(String raw) {
        String k = md5(raw);
        Object[] hit = IMG_MEM.get(k);
        if (hit != null) return hit;
        File dir = imgDir;
        if (dir == null) return null;
        File f = new File(dir, k);
        if (!f.exists()) return null;
        try {
            byte[] bytes = readBytes(f);
            File t = new File(dir, k + ".type");
            String type = t.exists() ? readText(t) : "image/jpeg";
            f.setLastModified(System.currentTimeMillis());
            Object[] out = {type, bytes};
            IMG_MEM.put(k, out);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static void storeImage(String raw, String type, byte[] bytes) {
        String k = md5(raw);
        IMG_MEM.put(k, new Object[]{type, bytes});
        File dir = imgDir;
        if (dir == null) return;
        try {
            write(new File(dir, k + ".type"), type.getBytes(StandardCharsets.UTF_8));
            write(new File(dir, k), bytes);
            File[] files = dir.listFiles(file -> !file.getName().endsWith(".type"));
            if (files == null) return;
            long total = 0;
            for (File file : files) total += file.length();
            if (total <= IMG_DISK_MAX) return;
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File file : files) {
                if (total <= IMG_DISK_MAX * 8 / 10) break;
                total -= file.length();
                file.delete();
                new File(dir, file.getName() + ".type").delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static Response fetchImage(String raw) {
        Object[] cached = cachedImage(raw);
        if (cached != null) return imageResponse((String) cached[0], (byte[]) cached[1]);
        String[] parts = raw.split("@");
        String url = parts[0];
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) headers.put(parts[i].substring(0, eq).trim(), parts[i].substring(eq + 1).trim());
        }
        boolean ua = false;
        boolean referer = false;
        for (String k : headers.keySet()) {
            if (k.equalsIgnoreCase("User-Agent")) ua = true;
            if (k.equalsIgnoreCase("Referer")) referer = true;
        }
        if (!ua) headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
        if (!referer) {
            try {
                URI u = new URI(url);
                if (u.getScheme() != null && u.getHost() != null) headers.put("Referer", u.getScheme() + "://" + u.getHost() + "/");
            } catch (Exception ignored) {
            }
        }
        try {
            Request.Builder b = new Request.Builder().url(url);
            for (Map.Entry<String, String> e : headers.entrySet()) b.header(e.getKey(), e.getValue());
            try (okhttp3.Response r = OkHttp.client(15_000).newCall(b.build()).execute()) {
                byte[] bytes = r.body().bytes();
                if (!r.isSuccessful() || bytes.length == 0) {
                    BridgeLog.w("[JF] 封面 " + r.code() + " " + cut(url, 160));
                    return text(Response.Status.NOT_FOUND, "no image");
                }
                String type = r.header("Content-Type");
                if (type == null || !type.startsWith("image/")) type = "image/jpeg";
                storeImage(raw, type, bytes);
                return imageResponse(type, bytes);
            }
        } catch (Throwable t) {
            BridgeLog.w("[JF] 封面失败 " + cut(url, 160) + ": " + t);
            return text(Response.Status.NOT_FOUND, "no image");
        }
    }

    // ---------- spider calls ----------

    /** Runs fn for every key in parallel, returns what finished within timeoutSec, in key order. */
    private static <T> LinkedHashMap<String, T> fanOut(List<String> keys, long timeoutSec, Function<String, T> fn) {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000;
        LinkedHashMap<String, Future<T>> futures = new LinkedHashMap<>();
        for (String k : keys) futures.put(k, POOL.submit(() -> fn.apply(k)));
        LinkedHashMap<String, T> out = new LinkedHashMap<>();
        for (Map.Entry<String, Future<T>> e : futures.entrySet()) {
            long left = Math.max(1, deadline - System.currentTimeMillis());
            try {
                T v = e.getValue().get(left, TimeUnit.MILLISECONDS);
                if (v != null) out.put(e.getKey(), v);
            } catch (Exception ex) {
                e.getValue().cancel(true);
            }
        }
        return out;
    }

    private static Home homeOf(String site) {
        Home cached = HOMES.get(site);
        if (cached != null) return cached;
        JsonObject home;
        try {
            home = bridge().home(site, HOME_MS).value;
        } catch (BridgeException e) {
            BridgeLog.w("[JF] home " + site + " " + e.code + ": " + e.getMessage());
            return null;
        }
        List<String[]> classes = new ArrayList<>();
        JsonElement cls = home.get("class");
        if (cls != null && cls.isJsonArray()) for (JsonElement el : cls.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            String tid = str(el.getAsJsonObject(), "type_id");
            String name = str(el.getAsJsonObject(), "type_name");
            if (!tid.isEmpty()) classes.add(new String[]{tid, name.isEmpty() ? tid : name});
        }
        List<Vod> list = parseList(site, home);
        Home h = new Home(classes, list);
        // Only cache a real answer, so a transient failure is retried on the next browse.
        if (!classes.isEmpty() || !list.isEmpty()) HOMES.put(site, h);
        else BridgeLog.w("[JF] " + site + " homeContent 无分类也无推荐");
        return h;
    }

    private static final class Page {
        final List<Vod> list;
        final int pageCount;

        Page(List<Vod> list, int pageCount) {
            this.list = list;
            this.pageCount = pageCount;
        }
    }

    /** One CatVod page; pageCount is the site's pagecount, or a guess when it gives none. */
    private static Page category(String site, String tid, int page) {
        String ck = site + "|" + tid + "|" + page;
        Object[] hit = PAGES.get(ck);
        if (hit != null) return (Page) hit[0];
        JsonObject res;
        try {
            res = bridge().category(site, tid, page, new HashMap<>(), CATEGORY_MS).value;
        } catch (BridgeException e) {
            BridgeLog.w("[JF] category " + site + " " + tid + " p" + page + " " + e.code + ": " + e.getMessage());
            return new Page(Collections.emptyList(), page - 1);
        }
        List<Vod> list = parseList(site, res);
        int pc = intOf(res, "pagecount");
        int pageCount = pc > 0 ? pc : list.isEmpty() ? page - 1 : page + 1;
        if (page == 1 && !list.isEmpty()) PAGE_SIZES.put(site + "|" + tid, list.size());
        Page out = new Page(list, pageCount);
        if (!list.isEmpty()) PAGES.put(ck, new Object[]{out});
        return out;
    }

    /** Jellyfin pages by StartIndex/Limit, CatVod by page number: map the window onto CatVod pages. Returns (vods, total). */
    private static Page categoryWindow(String site, String tid, int start, Integer limit) {
        Page first = category(site, tid, 1);
        Integer size = PAGE_SIZES.get(site + "|" + tid);
        if (size == null || size <= 0) return new Page(Collections.emptyList(), 0);
        int want = limit != null ? limit : size;
        List<Vod> out = new ArrayList<>();
        int page = start / size + 1;
        int skip = start % size;
        int pageCount = first.pageCount;
        while (out.size() < want) {
            Page p = category(site, tid, page);
            pageCount = p.pageCount;
            if (p.list.isEmpty()) break;
            out.addAll(p.list.subList(Math.min(skip, p.list.size()), p.list.size()));
            skip = 0;
            if (page >= p.pageCount) break;
            page++;
        }
        List<Vod> window = out.size() > want ? new ArrayList<>(out.subList(0, want)) : out;
        int total = pageCount > 0 ? pageCount * size : start + window.size();
        return new Page(window, Math.max(total, start + window.size()));
    }

    /** Global search: every searchable Java site in parallel, 15 s overall, results tagged with the site name. */
    private static List<Vod> search(String term) {
        Object[] hit = SEARCHES.get(term);
        if (hit != null && System.currentTimeMillis() - (Long) hit[0] < 10 * 60_000) {
            @SuppressWarnings("unchecked") List<Vod> list = (List<Vod>) hit[1];
            return list;
        }
        List<String> keys = new ArrayList<>();
        for (SiteInfo s : served()) if (s.searchable) keys.add(s.key);
        long started = System.currentTimeMillis();
        LinkedHashMap<String, List<Vod>> results = fanOut(keys, 15, k -> {
            try {
                return parseList(k, bridge().search(k, term, 1, SEARCH_MS).value);
            } catch (BridgeException e) {
                return null;
            }
        });
        // Keep config order so the user's preferred sites come first; 豆瓣-style msearch: items are jump links, not videos.
        List<Vod> list = new ArrayList<>();
        int sitesWithHits = 0;
        for (String k : keys) {
            List<Vod> got = results.get(k);
            if (got == null || got.isEmpty()) continue;
            sitesWithHits++;
            for (Vod v : got) if (!v.id.startsWith(MSEARCH)) list.add(v.withName(v.name + " · " + shortName(k)));
        }
        BridgeLog.i("[JF] 搜索「" + term + "」" + keys.size() + " 站，" + sitesWithHits + " 站有结果，共 " + list.size() + " 条，用时 " + (System.currentTimeMillis() - started) + " ms");
        if (!list.isEmpty()) SEARCHES.put(term, new Object[]{System.currentTimeMillis(), list});
        return list;
    }

    /** Title key for merging: case, full-width forms, spaces and punctuation ignored. */
    private static String titleKey(String name) {
        StringBuilder b = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            char x = c >= '\uFF01' && c <= '\uFF5E' ? (char) (c - 0xFEE0) : c;
            if (Character.isLetterOrDigit(x)) b.append(x);
        }
        return b.toString();
    }

    private static String baseName(String name) {
        int i = name.lastIndexOf(" · ");
        return i >= 0 ? name.substring(0, i) : name;
    }

    /** Search results grouped by title, config order kept; each entry is one vod or several from different sites. */
    private static List<List<Vod>> searchEntries(String term) {
        LinkedHashMap<String, List<Vod>> groups = new LinkedHashMap<>();
        for (Vod v : search(term)) groups.computeIfAbsent(titleKey(baseName(v.name)), k -> new ArrayList<>()).add(v);
        List<List<Vod>> out = new ArrayList<>();
        for (List<Vod> g : groups.values()) out.add(g.size() > MAX_GROUP ? new ArrayList<>(g.subList(0, MAX_GROUP)) : g);
        return out;
    }

    private static String entryName(List<Vod> g) {
        return g.size() == 1 ? g.get(0).name : baseName(g.get(0).name) + " [" + g.size() + "源]";
    }

    private static void remember(String id, String name, List<Vod> g) {
        for (Vod v : g) OWNERS.put(v.key(), new String[]{id, name});
    }

    private static JsonObject entryDto(List<Vod> g) {
        if (g.size() == 1) return seriesDto(g.get(0), null);
        String id = groupId(g);
        String name = entryName(g);
        remember(id, name, g);
        return groupBase(id, name, g);
    }

    private static JsonObject groupBase(String id, String name, List<Vod> g) {
        String pic = "";
        for (Vod v : g) if (pic.isEmpty() && v.pic.startsWith("http")) pic = v.pic;
        O o = new O().p("Name", name).p("ServerId", SERVER_ID).p("Id", id).p("Type", "Series").p("IsFolder", true)
                .p("MediaType", "Unknown").p("LocationType", "Remote").p("UserData", userData(id)).p("ImageTags", imageTags(pic))
                .p("BackdropImageTags", new JsonArray()).p("PrimaryImageAspectRatio", POSTER_RATIO);
        for (Vod v : g) {
            Double r = ratingOf(v.remarks);
            if (r != null) {
                o.p("CommunityRating", r);
                break;
            }
        }
        for (Vod v : g) {
            Integer y = year(v.year);
            if (y != null) {
                o.p("ProductionYear", y);
                break;
            }
        }
        return o.o;
    }

    /**
     * Members of a merged entry: a search group (ids in the key), or an msearch: vod (豆瓣-style jump item), which
     * opens as the same title found on the other sites. Null for a plain series.
     */
    private static List<Vod> groupMembers(String id, List<String> key) {
        if ("group".equals(kind(key))) {
            List<Vod> out = new ArrayList<>();
            for (int i = 2; i < key.size(); i++) {
                String t = key.get(i);
                int sep = t.indexOf('\u0002');
                if (sep < 0) continue;
                String s = t.substring(0, sep);
                String v = t.substring(sep + 1);
                Vod known = VODS.get(s + "|" + v);
                out.add(known != null ? known : new Vod(s, v, "", "", "", ""));
            }
            return out;
        }
        if (!"series".equals(kind(key)) || !key.get(2).startsWith(MSEARCH)) return null;
        List<Vod> cached = MSEARCH_MEMBERS.get(id);
        if (cached != null) return cached;
        Vod self = VODS.get(key.get(0) + "|" + key.get(2));
        String name = self != null && !self.name.isEmpty() ? self.name : key.get(2).substring(MSEARCH.length());
        String want = titleKey(name);
        List<Vod> out = new ArrayList<>();
        Set<String> sites = new HashSet<>();
        for (Vod v : search(name)) {
            if (out.size() >= MAX_GROUP) break;
            if (titleKey(baseName(v.name)).equals(want) && sites.add(v.site)) out.add(v);
        }
        BridgeLog.i("[JF] msearch「" + name + "」在其他站找到 " + out.size() + " 个同名");
        if (!out.isEmpty()) {
            MSEARCH_MEMBERS.put(id, out);
            remember(id, self != null ? self.name : name, out);
        }
        return out;
    }

    private static JsonObject groupDto(String id, List<Vod> g) {
        List<String> key = lookup(id);
        String fallback = "series".equals(kind(key)) ? null : (g.isEmpty() ? "" : baseName(g.get(0).name) + " [" + g.size() + "源]");
        if (fallback == null) {
            Vod self = VODS.get(key.get(0) + "|" + key.get(2));
            fallback = self != null ? self.name : key.get(2).substring(MSEARCH.length());
        }
        String[] owner = g.isEmpty() ? null : OWNERS.get(g.get(0).key());
        String name = owner != null ? owner[1] : fallback;
        for (Vod v : g) OWNERS.putIfAbsent(v.key(), new String[]{id, name});
        JsonObject base = groupBase(id, name, g);
        if ("series".equals(kind(key))) {
            // Keep the msearch item's own poster and rating from the list it came from.
            Vod self = VODS.get(key.get(0) + "|" + key.get(2));
            if (self != null) base = seriesDto(self, null);
            base.addProperty("Name", name);
        }
        List<Detail> ds = groupDetails(id, g);
        Detail first = null;
        for (Detail d : ds) if (!d.content.trim().isEmpty()) {
            first = d;
            break;
        }
        if (first == null && !ds.isEmpty()) first = ds.get(0);
        if (first != null) {
            StringBuilder src = new StringBuilder();
            for (Detail d : ds) src.append(src.length() == 0 ? "" : "、").append(shortName(d.vod.site));
            int seasons = 0;
            for (Detail d : ds) seasons += d.flags.size();
            base.addProperty("Overview", "来源：" + src + "\n\n" + first.content);
            base.addProperty("ChildCount", seasons);
            base.add("People", people(first));
        } else if (g.isEmpty()) {
            base.addProperty("Overview", "其他站点没有找到同名影片");
        }
        return base;
    }

    /** Details of every site in a merged entry, fetched in parallel; sites that fail or time out are left out. */
    private static List<Detail> groupDetails(String id, List<Vod> members) {
        List<Detail> cached = GROUP_CACHE.get(id);
        if (cached != null) return cached;
        if (members.isEmpty()) return Collections.emptyList();
        String[] name = OWNERS.get(members.get(0).key());
        for (Vod v : members) OWNERS.putIfAbsent(v.key(), name != null ? name : new String[]{id, v.name});
        List<String> tags = new ArrayList<>();
        for (Vod v : members) tags.add(v.site + "\u0002" + v.id);
        LinkedHashMap<String, Detail> got = fanOut(tags, 15, t -> detail(t.substring(0, t.indexOf('\u0002')), t.substring(t.indexOf('\u0002') + 1), false));
        List<Detail> list = new ArrayList<>(got.values());
        BridgeLog.i("[JF] 合并条目 " + members.size() + " 源，详情成功 " + list.size());
        if (list.size() == members.size()) GROUP_CACHE.put(id, list);
        return list;
    }

    /**
     * SenPlayer opens a detail page with several requests at once (item, seasons, episodes...): concurrent callers
     * share one detailContent call, a fast failure is retried once, and failures are remembered for a minute so
     * posters and repeated opens do not keep hitting the site.
     */
    private static Detail detail(String site, String vodId, boolean background) {
        String k = site + "|" + vodId;
        Detail cached = DETAILS.get(k);
        if (cached != null) return cached;
        if (!background) DETAIL_FAILED_AT.remove(k);
        else if (failedRecently(k)) return null;
        CompletableFuture<Detail> mine = new CompletableFuture<>();
        CompletableFuture<Detail> existing = INFLIGHT.putIfAbsent(k, mine);
        if (existing != null) {
            try {
                return existing.get(DETAIL_MS * 2 + 2_000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return null;
            }
        }
        Detail d = null;
        try {
            long t0 = System.currentTimeMillis();
            d = loadDetail(site, vodId);
            long ms = System.currentTimeMillis() - t0;
            if (d == null && ms < 4000 && !background) {
                BridgeLog.w("[JF] detail " + k + " 第一次失败（" + ms + " ms），重试一次");
                d = loadDetail(site, vodId);
            }
            if (d == null) DETAIL_FAILED_AT.put(k, System.currentTimeMillis());
            return d;
        } finally {
            mine.complete(d);
            INFLIGHT.remove(k, mine);
        }
    }

    private static Detail loadDetail(String site, String vodId) {
        String k = site + "|" + vodId;
        JsonObject res;
        try {
            res = bridge().detail(site, vodId, DETAIL_MS).value;
        } catch (BridgeException e) {
            BridgeLog.w("[JF] detail " + k + " " + e.code + ": " + e.getMessage());
            return null;
        }
        JsonElement listEl = res.get("list");
        if (listEl == null || !listEl.isJsonArray() || listEl.getAsJsonArray().isEmpty() || !listEl.getAsJsonArray().get(0).isJsonObject()) {
            BridgeLog.w("[JF] detail 无 list[0]: " + cut(res.toString(), 300));
            return null;
        }
        JsonObject o = listEl.getAsJsonArray().get(0).getAsJsonObject();
        Vod v = toVod(site, o);
        if (v == null) v = new Vod(site, vodId, str(o, "vod_name"), str(o, "vod_pic"), str(o, "vod_remarks"), str(o, "vod_year"));
        // Search results carry a "· 站点" suffix; keep it so the user sees which source they are in.
        Vod listed = VODS.get(k);
        String shown = listed != null && listed.name.startsWith(v.name) ? listed.name : v.name;
        List<String> flags = new ArrayList<>();
        List<List<String[]>> eps = new ArrayList<>();
        JsonElement cf = o.get("cfjs_flags");
        if (cf != null && cf.isJsonArray()) for (JsonElement fe : cf.getAsJsonArray()) {
            if (!fe.isJsonObject()) continue;
            JsonObject fo = fe.getAsJsonObject();
            List<String[]> list = new ArrayList<>();
            JsonElement el = fo.get("episodes");
            if (el != null && el.isJsonArray()) for (JsonElement ee : el.getAsJsonArray()) {
                if (ee.isJsonObject()) list.add(new String[]{str(ee.getAsJsonObject(), "name"), str(ee.getAsJsonObject(), "id")});
            }
            if (list.isEmpty()) continue;
            flags.add(str(fo, "flag"));
            eps.add(list);
        }
        Detail d = new Detail(v.withName(shown), str(o, "vod_content"), str(o, "vod_actor"), str(o, "vod_director"), flags, eps);
        DETAILS.put(k, d);
        VODS.putIfAbsent(k, v.id.equals(vodId) ? v : new Vod(site, vodId, v.name, v.pic, v.remarks, v.year));
        return d;
    }

    private static List<Vod> parseList(String site, JsonObject res) {
        List<Vod> out = new ArrayList<>();
        JsonElement list = res == null ? null : res.get("list");
        if (list == null || !list.isJsonArray()) return out;
        for (JsonElement el : list.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            Vod v = toVod(site, el.getAsJsonObject());
            if (v == null) continue;
            out.add(v);
            VODS.putIfAbsent(v.key(), v);
        }
        return out;
    }

    private static Vod toVod(String site, JsonObject o) {
        String id = str(o, "vod_id");
        if (id.isEmpty()) return null;
        return new Vod(site, id, str(o, "vod_name"), str(o, "vod_pic"), str(o, "vod_remarks"), str(o, "vod_year"));
    }

    // ---------- helpers ----------

    private static JsonObject parseObject(String body) {
        try {
            JsonElement el = Json.parse(body == null || body.isEmpty() ? "{}" : body);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement el = o == null ? null : o.get(k);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return "";
        return el.getAsString();
    }

    private static int intOf(JsonObject o, String k) {
        try {
            JsonElement el = o.get(k);
            return el != null && el.isJsonPrimitive() ? el.getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long longOf(JsonObject o, String k) {
        try {
            JsonElement el = o.get(k);
            return el != null && el.isJsonPrimitive() ? el.getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static boolean bool(JsonObject o, String k) {
        try {
            JsonElement el = o.get(k);
            return el != null && el.isJsonPrimitive() && el.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }

    static String readText(File f) throws Exception {
        return new String(readBytes(f), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(File f) throws Exception {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void write(File f, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
    }

    // ---------- responses ----------

    private static Response json(JsonElement o) {
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString());
    }

    private static Response text(Response.Status st, String s) {
        return text(st, s, "text/plain; charset=utf-8");
    }

    private static Response text(Response.Status st, String s, String mime) {
        return NanoHTTPD.newFixedLengthResponse(st, mime, s);
    }

    private static Response noContent() {
        return NanoHTTPD.newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "");
    }

    private static Response redirect(String url) {
        Response r = NanoHTTPD.newFixedLengthResponse(status(302), "text/plain", "");
        r.addHeader("Location", url);
        return r;
    }

    static Response.IStatus status(int code) {
        Response.Status known = Response.Status.lookup(code);
        if (known != null) return known;
        return new Response.IStatus() {
            @Override
            public String getDescription() {
                return code + (code == 302 ? " Found" : " Upstream");
            }

            @Override
            public int getRequestStatus() {
                return code;
            }
        };
    }

    static String md5(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : digest) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
