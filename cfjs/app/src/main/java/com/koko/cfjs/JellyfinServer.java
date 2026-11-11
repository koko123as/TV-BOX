package com.koko.cfjs;

import com.fongmi.android.tv.App;
import com.github.catvod.Proxy;

import fi.iki.elonen.NanoHTTPD;

/**
 * Jellyfin-compatible server for SenPlayer on the same phone. Binds 127.0.0.1:9978 only (the CatVod default
 * port, so spider proxy URLs land here too). Separate from the token API on 19978: no token, any user,
 * empty password, every method. Paths:
 *   /proxy  CatVod local proxy, see {@link LocalProxy}
 *   /file/  read-only files under shared storage (upstream /file)
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
            NetDisk.logStorage("start");
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
            if (uri.startsWith("/proxy")) return LocalProxy.serve(session);
            if (uri.startsWith("/file/") && session.getMethod() == Method.GET) return file(session, uri);
            if (uri.equals("/hls")) return JfRelay.serve(session);
            return JellyfinApi.handle(session, uri);
        } catch (Throwable e) {
            BridgeLog.w("[JF] " + uri + " failed: " + e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, String.valueOf(e));
        }
    }

    /**
     * Read-only upstream-style /file/... : files under the shared storage root (upstream server/process/Local),
     * which configs reference as http://127.0.0.1:9978/file/TVBox/xxx.txt for drive cookies/tokens.
     */
    private Response file(IHTTPSession session, String uri) {
        String rel = uri.substring("/file".length());
        java.io.File root = com.github.catvod.utils.Path.root();
        java.io.File target = new java.io.File(root, rel);
        try {
            String base = root.getCanonicalPath();
            String path = target.getCanonicalPath();
            if (!path.equals(base) && !path.startsWith(base + java.io.File.separator)) return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "outside storage");
            if (!target.isFile()) {
                NetDisk.w("file " + rel + " -> 404（文件不存在或没有存储权限: " + NetDisk.storageStatus() + "）");
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found");
            }
            NetDisk.i("file " + rel + " -> 200 " + target.length() + "B");
            return newFixedLengthResponse(Response.Status.OK, getMimeTypeForFile(path), new java.io.FileInputStream(target), target.length());
        } catch (Exception e) {
            NetDisk.w("file " + rel + " -> " + e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, String.valueOf(e));
        }
    }
}
