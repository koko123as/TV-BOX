package com.koko.cfjs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * CFJS HTTP API. Separate from upstream {@code Nano}/{@code /spider-api}; binds 127.0.0.1 only, so it is reachable
 * on this device and through cloudflared, never from the LAN. Every path except /api/v1/health needs the
 * {@code X-CFJS-Token} header. Videos are never proxied: /play returns the source site's own URL and headers.
 */
public final class BridgeServer extends NanoHTTPD {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 19978;
    public static final String VERSION = "1";
    private static volatile BridgeServer instance;

    private BridgeServer() {
        super(HOST, PORT);
    }

    public static synchronized boolean start() {
        if (running()) return true;
        BridgeServer server = new BridgeServer();
        try {
            server.start(SOCKET_READ_TIMEOUT, true);
            instance = server;
            BridgeLog.i("API started http://" + HOST + ":" + PORT);
            return true;
        } catch (Exception e) {
            BridgeLog.w("API start failed: " + e);
            return false;
        }
    }

    public static synchronized void shutdown() {
        if (instance == null) return;
        instance.stop();
        instance = null;
        BridgeLog.i("API stopped");
    }

    public static boolean running() {
        BridgeServer server = instance;
        return server != null && server.isAlive();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String path = session.getUri();
        long start = System.currentTimeMillis();
        try {
            if (session.getMethod() != Method.GET) return json(Response.Status.METHOD_NOT_ALLOWED, error("METHOD", "GET only"));
            if ("/api/v1/health".equals(path)) return json(Response.Status.OK, health());
            if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, error("UNAUTHORIZED", "missing or wrong X-CFJS-Token"));
            Map<String, String> q = params(session);
            JsonObject body = switch (path) {
                case "/api/v1/sites" -> sites();
                case "/api/v1/home" -> wrap(SpiderBridge.get().home(q.get("site")));
                case "/api/v1/category" -> wrap(SpiderBridge.get().category(q.get("site"), q.get("tid"), number(q.get("page")), filters(q.get("filters"))));
                case "/api/v1/search" -> wrap(SpiderBridge.get().search(q.get("site"), q.get("wd"), number(q.get("page"))));
                case "/api/v1/detail" -> wrap(SpiderBridge.get().detail(q.get("site"), q.get("id")));
                case "/api/v1/play" -> wrap(SpiderBridge.get().play(q.get("site"), q.get("flag"), q.get("id")));
                default -> null;
            };
            if (body == null) return json(Response.Status.NOT_FOUND, error("NOT_FOUND", path));
            BridgeLog.i("API " + path + " 200 " + (System.currentTimeMillis() - start) + "ms");
            return json(Response.Status.OK, body);
        } catch (BridgeException e) {
            BridgeLog.w("API " + path + " " + e.code + " " + e.getMessage());
            return json(status(e.code), error(e.code, e.getMessage()));
        } catch (Exception e) {
            BridgeLog.w("API " + path + " " + e);
            return json(Response.Status.INTERNAL_ERROR, error("INTERNAL", e.getClass().getSimpleName()));
        }
    }

    private static Response.Status status(String code) {
        return switch (code) {
            case "BAD_REQUEST", "UNSUPPORTED_SITE" -> Response.Status.BAD_REQUEST;
            case "SITE_NOT_FOUND" -> Response.Status.NOT_FOUND;
            case "BUSY" -> Response.Status.TOO_MANY_REQUESTS;
            case "CONFIG_EMPTY" -> Response.Status.SERVICE_UNAVAILABLE;
            default -> Response.Status.INTERNAL_ERROR;
        };
    }

    private static boolean authorized(IHTTPSession session) {
        String header = session.getHeaders().get("x-cfjs-token");
        if (header == null) return false;
        return MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), Prefs.token().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> params(IHTTPSession session) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : session.getParameters().entrySet()) {
            if (!entry.getValue().isEmpty()) out.put(entry.getKey(), entry.getValue().get(0));
        }
        return out;
    }

    private static int number(String value) {
        try {
            return value == null ? 1 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static Map<String, String> filters(String json) throws BridgeException {
        if (json == null || json.isEmpty()) return new HashMap<>();
        try {
            return com.github.catvod.utils.Json.toMap(com.github.catvod.utils.Json.parse(json));
        } catch (RuntimeException e) {
            throw new BridgeException("BAD_REQUEST", "filters must be a JSON object");
        }
    }

    private static JsonObject health() {
        JsonObject body = new JsonObject();
        body.addProperty("ok", true);
        body.addProperty("service", "cfjs");
        body.addProperty("version", VERSION);
        body.addProperty("configLoaded", Engine.get().loaded());
        return body;
    }

    private static JsonObject sites() {
        JsonArray items = new JsonArray();
        for (SiteInfo site : Engine.get().sites()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", site.key);
            item.addProperty("name", site.name);
            item.addProperty("kind", site.kind());
            item.addProperty("supported", site.supported());
            item.addProperty("searchable", site.searchable);
            items.add(item);
        }
        JsonObject body = new JsonObject();
        body.addProperty("count", items.size());
        body.add("sites", items);
        return body;
    }

    private static JsonObject wrap(SpiderBridge.Timed timed) {
        JsonObject body = new JsonObject();
        body.addProperty("elapsedMs", timed.elapsedMs);
        body.add("data", timed.value);
        return body;
    }

    private static JsonObject error(String code, String message) {
        JsonObject inner = new JsonObject();
        inner.addProperty("code", code);
        inner.addProperty("message", message == null ? "" : message);
        JsonObject body = new JsonObject();
        body.add("error", inner);
        return body;
    }

    private static Response json(Response.Status status, JsonObject body) {
        Response response = newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString());
        response.addHeader("Cache-Control", "no-store");
        return response;
    }
}
