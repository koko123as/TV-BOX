package com.koko.cfjs;

import com.fongmi.android.tv.App;
import com.github.catvod.Proxy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Jellyfin-compatible server for SenPlayer on the same phone. Binds 127.0.0.1:9978 only (the CatVod default
 * port, so spider proxy URLs land here too). Separate from the token API on 19978: no token, any user,
 * empty password, every method. Paths:
 *   /proxy  CatVod local proxy (siteKey -> that site's spider.proxy, otherwise the jar's static Proxy.proxy)
 *   /hls    header relay for HLS segments (see {@link JfRelay})
 *   else    Jellyfin API (see {@link JellyfinApi})
 */
public final class JellyfinServer extends NanoHTTPD {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 9978;
    public static final String BASE = "http://" + HOST + ":" + PORT;
    private static volatile JellyfinServer instance;

    private JellyfinServer() {
        super(HOST, PORT);
    }

    public static synchronized boolean startServer() {
        if (running()) return true;
        JellyfinApi.init(App.get().getFilesDir(), App.get().getCacheDir());
        JellyfinServer server = new JellyfinServer();
        try {
            server.start(SOCKET_READ_TIMEOUT, true);
            instance = server;
            // Spider jars build their local proxy URLs from this port (upstream Server does the same).
            Proxy.set(PORT);
            BridgeLog.i("Jellyfin started " + BASE + "（SenPlayer: 127.0.0.1 端口 " + PORT + "，用户名任意，密码留空）");
            return true;
        } catch (Exception e) {
            BridgeLog.w("Jellyfin start failed (port " + PORT + " in use?): " + e);
            return false;
        }
    }

    public static synchronized void shutdown() {
        if (instance == null) return;
        instance.stop();
        instance = null;
        BridgeLog.i("Jellyfin stopped");
    }

    public static boolean running() {
        JellyfinServer server = instance;
        return server != null && server.isAlive();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        try {
            if (uri.startsWith("/proxy")) return proxy(session);
            if (uri.equals("/hls")) return JfRelay.serve(session);
            return JellyfinApi.handle(session, uri);
        } catch (Throwable e) {
            BridgeLog.w("[JF] " + uri + " failed: " + e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, String.valueOf(e));
        }
    }

    /** Same parameter set as upstream server/process/Proxy: query, headers, then the parsed body. */
    private Response proxy(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        if (session.getMethod() == Method.POST || session.getMethod() == Method.PUT) {
            try {
                session.parseBody(files);
            } catch (Exception ignored) {
            }
        }
        Map<String, String> params = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : session.getParameters().entrySet()) {
            if (!entry.getValue().isEmpty()) params.put(entry.getKey(), entry.getValue().get(0));
        }
        params.putAll(session.getHeaders());
        params.putAll(files);
        Object[] rs;
        String key = params.get("siteKey");
        if (key != null) {
            SiteInfo site = Engine.get().site(key);
            if (site == null || !site.supported()) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "unknown site " + key);
            rs = Engine.get().spider(site).proxy(params);
        } else {
            rs = Engine.get().proxy(params);
        }
        return toResponse(rs);
    }

    @SuppressWarnings("unchecked")
    private static Response toResponse(Object[] rs) {
        if (rs == null || rs.length == 0) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Invalid proxy response");
        if (rs[0] instanceof Response response) return response;
        if (rs.length < 3) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Invalid proxy response");
        int code = rs[0] instanceof Number number ? number.intValue() : 500;
        String mime = rs[1] == null ? "application/octet-stream" : rs[1].toString();
        Object body = rs[2];
        Response response;
        if (body instanceof InputStream stream) response = newChunkedResponse(JellyfinApi.status(code), mime, stream);
        else if (body instanceof byte[] bytes) response = newFixedLengthResponse(JellyfinApi.status(code), mime, new ByteArrayInputStream(bytes), bytes.length);
        else response = newFixedLengthResponse(JellyfinApi.status(code), mime, body == null ? "" : body.toString());
        if (rs.length > 3 && rs[3] instanceof Map<?, ?> headers) {
            for (Map.Entry<?, ?> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                String name = entry.getKey().toString();
                if (!name.equalsIgnoreCase("Content-Length") && !name.equalsIgnoreCase("Transfer-Encoding")) response.addHeader(name, entry.getValue().toString());
            }
        }
        return response;
    }
}
