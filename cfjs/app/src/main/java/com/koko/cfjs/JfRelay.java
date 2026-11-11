package com.koko.cfjs;

import android.util.Base64;

import com.github.catvod.net.OkHttp;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Ported from SpiderHost HlsRelay. Plays a URL that needs request headers (Referer/User-Agent/Cookie) through
 * 127.0.0.1:9978, since SenPlayer cannot be told the headers:
 * single files are passed through with the headers and Range; an HLS playlist is rewritten so every segment,
 * sub-playlist and key goes back through /hls?h=&lt;header set&gt;&amp;u=&lt;base64 url&gt; with the same headers.
 * Only used for header-bound URLs; everything else is a 302 to the source.
 */
final class JfRelay {

    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");
    private static final ConcurrentHashMap<String, Map<String, String>> HEADER_SETS = new ConcurrentHashMap<>();

    private JfRelay() {
    }

    private static OkHttpClient http() {
        return OkHttp.client(60_000);
    }

    private static String register(Map<String, String> headers) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : headers.entrySet()) lines.add(e.getKey() + ":" + e.getValue());
        Collections.sort(lines);
        String id = JellyfinApi.md5(String.join("\n", lines)).substring(0, 16);
        HEADER_SETS.put(id, headers);
        return id;
    }

    /** GET /hls?h=..&amp;u=.. */
    static Response serve(IHTTPSession s) {
        List<String> u = s.getParameters().get("u");
        if (u == null || u.isEmpty()) return bad("missing u");
        String url;
        try {
            url = new String(Base64.decode(u.get(0), Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return bad("bad u");
        }
        List<String> h = s.getParameters().get("h");
        Map<String, String> headers = h == null || h.isEmpty() ? null : HEADER_SETS.get(h.get(0));
        return fetch(s, url, headers == null ? Collections.emptyMap() : headers, null);
    }

    static Response fetch(IHTTPSession s, String url, Map<String, String> headers, String logLabel) {
        Request.Builder b = new Request.Builder().url(url);
        for (Map.Entry<String, String> e : headers.entrySet()) b.header(e.getKey(), e.getValue());
        String range = s.getHeaders().get("range");
        if (range != null) b.header("Range", range);
        okhttp3.Response resp;
        try {
            resp = http().newCall(b.build()).execute();
        } catch (Exception e) {
            BridgeLog.w((logLabel == null ? "[HLS]" : logLabel) + " upstream failed: " + e);
            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "upstream failed");
        }
        int code = resp.code();
        String mime = resp.header("Content-Type", "");
        String path = url.split("\\?", 2)[0].toLowerCase();
        boolean playlist = path.endsWith(".m3u8") || mime.toLowerCase().contains("mpegurl");
        if (playlist && resp.isSuccessful()) {
            String text;
            try {
                text = resp.body().string();
            } catch (Exception e) {
                resp.close();
                return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "read failed");
            }
            String finalUrl = resp.request().url().toString();
            resp.close();
            String out = rewrite(text, finalUrl, register(headers));
            if (logLabel != null) BridgeLog.i(logLabel + " HLS 改写，请求头 " + headers.keySet());
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", out);
        }
        if (!resp.isSuccessful()) BridgeLog.w((logLabel == null ? "[HLS]" : logLabel) + " upstream " + code + " for " + cut(url, 160));
        InputStream body = resp.body().byteStream();
        String type = mime.isEmpty() ? "application/octet-stream" : mime;
        String length = resp.header("Content-Length");
        Response.IStatus st = JellyfinApi.status(code);
        Response out;
        long len = -1;
        try {
            if (length != null) len = Long.parseLong(length);
        } catch (NumberFormatException ignored) {
        }
        out = len >= 0 ? NanoHTTPD.newFixedLengthResponse(st, type, body, len) : NanoHTTPD.newChunkedResponse(st, type, body);
        for (String k : new String[]{"Content-Range", "Accept-Ranges"}) {
            String v = resp.header(k);
            if (v != null) out.addHeader(k, v);
        }
        if (logLabel != null) BridgeLog.i(logLabel + " passthrough upstream=" + code + " len=" + length + " type=" + mime);
        return out;
    }

    private static String local(HttpUrl base, String ref, String hid) {
        HttpUrl resolved = base == null ? null : base.resolve(ref.trim());
        String abs = resolved == null ? ref.trim() : resolved.toString();
        String enc = Base64.encodeToString(abs.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP);
        return JellyfinServer.BASE + "/hls?h=" + hid + "&u=" + enc;
    }

    private static String rewrite(String text, String base, String hid) {
        HttpUrl baseUrl = HttpUrl.parse(base);
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (i > 0) out.append('\n');
            if (trimmed.isEmpty()) {
                out.append(line);
            } else if (trimmed.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(line);
                StringBuffer sb = new StringBuffer();
                while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement("URI=\"" + local(baseUrl, m.group(1), hid) + "\""));
                m.appendTail(sb);
                out.append(sb);
            } else {
                out.append(local(baseUrl, trimmed, hid));
            }
        }
        return out.toString();
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static Response bad(String msg) {
        return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, msg);
    }
}
