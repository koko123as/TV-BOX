package com.koko.cfjs;

import android.os.SystemClock;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

/**
 * CatVod local proxy on 127.0.0.1:9978/proxy, following upstream server/process/Proxy + BaseLoader.proxy:
 * params = query + request headers (lower-case names, e.g. "range") + parsed body; siteKey -> that site's
 * spider.proxy(params), otherwise the jar's static com.github.catvod.spider.Proxy.proxy(params).
 * The spider's Object[]{code, mime, body, headers} is streamed back as is: real status (206 etc.), every header
 * the spider set, Content-Length kept when the spider gave one (so players can seek), nothing buffered in memory.
 * Drive spiders (夸克/UC/阿里/115…) serve their multi-thread download through here, so every request is logged
 * as [PROXY] (throttled) with status, type, length, Content-Range, time and how many bytes really went out.
 */
final class LocalProxy {

    private static final int LOG_PER_MINUTE = 30;
    private static long windowStart;
    private static int windowCount;
    private static int windowDropped;

    private LocalProxy() {
    }

    /** Request that arrived on /proxy. */
    static Response serve(IHTTPSession session) {
        Map<String, String> files = new HashMap<>();
        if (session.getMethod() == NanoHTTPD.Method.POST || session.getMethod() == NanoHTTPD.Method.PUT) {
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
        return dispatch(params, "");
    }

    /**
     * A proxy URL that a spider returned from playerContent (http://127.0.0.1:9978/proxy?... or proxy://...),
     * served in-process for SenPlayer's request: its own query, plus SenPlayer's headers (Range!).
     */
    static Response serveUrl(IHTTPSession session, String url, String label) {
        Map<String, String> params = parseQuery(url);
        params.putAll(session.getHeaders());
        return dispatch(params, label);
    }

    static boolean isProxyUrl(String url) {
        return url != null && (url.startsWith("proxy://") || url.startsWith(JellyfinServer.BASE + "/proxy"));
    }

    /** Same decoding NanoHTTPD uses for /proxy (URLDecoder, '+' = space), so the spider sees the same values. */
    static Map<String, String> parseQuery(String url) {
        Map<String, String> params = new HashMap<>();
        int q = url.indexOf('?');
        String query = url.startsWith("proxy://") ? url.substring(8) : q < 0 ? "" : url.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        for (String part : query.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String name = decode(eq < 0 ? part : part.substring(0, eq));
            String value = eq < 0 ? "" : decode(part.substring(eq + 1));
            if (!params.containsKey(name)) params.put(name, value);
        }
        return params;
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return text;
        }
    }

    static Response dispatch(Map<String, String> params, String label) {
        long start = SystemClock.elapsedRealtime();
        String key = params.get("siteKey");
        String what = (label == null || label.isEmpty() ? "" : label + " ") + "do=" + params.get("do") + (key == null ? "" : " siteKey=" + key)
                + (params.containsKey("type") ? " type=" + params.get("type") : "") + (params.containsKey("range") ? " range=" + params.get("range") : "");
        Object[] rs;
        try {
            if (!Engine.get().loaded()) {
                log(true, "[PROXY] " + what + " -> 503 配置未加载");
                return text(503, "config not loaded");
            }
            if (key != null) {
                SiteInfo site = Engine.get().site(key);
                if (site == null || !site.supported()) {
                    log(true, "[PROXY] " + what + " -> 404 unknown site");
                    return text(404, "unknown site " + key);
                }
                rs = Engine.get().spider(site).proxy(params);
            } else {
                rs = Engine.get().proxy(params);
            }
        } catch (Throwable e) {
            Throwable cause = unwrap(e);
            log(true, "[PROXY] " + what + " -> spider threw " + cause.getClass().getName() + ": " + cause.getMessage() + at(cause));
            return text(502, "proxy error: " + cause);
        }
        return toResponse(rs, what, start);
    }

    @SuppressWarnings("unchecked")
    private static Response toResponse(Object[] rs, String what, long start) {
        long ms = SystemClock.elapsedRealtime() - start;
        if (rs == null || rs.length == 0) {
            log(true, "[PROXY] " + what + " -> spider returned null（没有处理这个请求的 proxy，或参数不对）" + ms + "ms");
            return text(500, "Invalid proxy response");
        }
        if (rs[0] instanceof Response response) {
            log(false, "[PROXY] " + what + " -> NanoHTTPD Response " + response.getStatus() + " " + ms + "ms");
            return response;
        }
        if (rs.length < 3) {
            log(true, "[PROXY] " + what + " -> invalid result length " + rs.length);
            return text(500, "Invalid proxy response");
        }
        int code = rs[0] instanceof Number number ? number.intValue() : 500;
        String mime = rs[1] == null ? "application/octet-stream" : rs[1].toString();
        Map<String, String> headers = new HashMap<>();
        if (rs.length > 3 && rs[3] instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) headers.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        long length = -1;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase("Content-Length")) {
                try {
                    length = Long.parseLong(e.getValue().trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        Object body = rs[2];
        String range = header(headers, "Content-Range");
        String summary = "[PROXY] " + what + " -> " + code + " " + mime + " len=" + (length >= 0 ? length : "?") + (range == null ? "" : " cr=" + range)
                + " body=" + (body == null ? "null" : body.getClass().getSimpleName()) + " " + ms + "ms";
        log(code >= 400, summary);
        Response response;
        if (body instanceof InputStream stream) {
            InputStream counted = new Counted(stream, what, code);
            response = length >= 0 ? NanoHTTPD.newFixedLengthResponse(JellyfinApi.status(code), mime, counted, length) : NanoHTTPD.newChunkedResponse(JellyfinApi.status(code), mime, counted);
        } else if (body instanceof byte[] bytes) {
            response = NanoHTTPD.newFixedLengthResponse(JellyfinApi.status(code), mime, new ByteArrayInputStream(bytes), bytes.length);
        } else {
            response = NanoHTTPD.newFixedLengthResponse(JellyfinApi.status(code), mime, body == null ? "" : body.toString());
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding") || name.equalsIgnoreCase("Connection") || name.equalsIgnoreCase("Content-Type")) continue;
            response.addHeader(name, entry.getValue());
        }
        if (header(headers, "Accept-Ranges") == null && (code == 206 || range != null)) response.addHeader("Accept-Ranges", "bytes");
        return response;
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    private static Response text(int code, String text) {
        Response r = NanoHTTPD.newFixedLengthResponse(JellyfinApi.status(code), NanoHTTPD.MIME_PLAINTEXT, text);
        r.addHeader("X-TV-Proxy-Error", "1");
        return r;
    }

    static Throwable unwrap(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause && (cause instanceof InvocationTargetException || cause.getClass() == RuntimeException.class)) {
            cause = cause.getCause();
        }
        return cause;
    }

    static String at(Throwable e) {
        StackTraceElement[] trace = e.getStackTrace();
        if (trace == null || trace.length == 0) return "";
        StringBuilder b = new StringBuilder(" @ ");
        for (int i = 0; i < Math.min(3, trace.length); i++) b.append(i == 0 ? "" : " < ").append(trace[i].getClassName()).append('.').append(trace[i].getMethodName());
        return b.toString();
    }

    /** Errors always go to the log; normal lines at most {@link #LOG_PER_MINUTE} a minute so a video does not flood it. */
    static synchronized void log(boolean error, String text) {
        long now = SystemClock.elapsedRealtime();
        if (now - windowStart > 60_000) {
            if (windowDropped > 0) BridgeLog.i("[PROXY] (上一分钟另有 " + windowDropped + " 条正常请求未记录)");
            windowStart = now;
            windowCount = 0;
            windowDropped = 0;
        }
        if (error) {
            BridgeLog.w(text);
            return;
        }
        if (windowCount++ < LOG_PER_MINUTE) BridgeLog.i(text);
        else windowDropped++;
    }

    /** Counts what really reaches the player; logs how the body ended (full, client closed, upstream error). */
    private static final class Counted extends FilterInputStream {

        private final String what;
        private final int code;
        private final long start = SystemClock.elapsedRealtime();
        private final AtomicBoolean done = new AtomicBoolean();
        private long bytes;
        private boolean eof;

        Counted(InputStream in, String what, int code) {
            super(in);
            this.what = what;
            this.code = code;
        }

        @Override
        public int read() throws IOException {
            try {
                int b = super.read();
                if (b < 0) eof = true;
                else bytes++;
                return b;
            } catch (IOException | RuntimeException e) {
                finish(e);
                throw e;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            try {
                int n = super.read(buffer, offset, count);
                if (n < 0) eof = true;
                else bytes += n;
                return n;
            } catch (IOException | RuntimeException e) {
                finish(e);
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            finish(null);
            super.close();
        }

        private void finish(Throwable error) {
            if (!done.compareAndSet(false, true)) return;
            long ms = SystemClock.elapsedRealtime() - start;
            if (error != null) log(true, "[PROXY] " + what + " body error after " + bytes + "B " + ms + "ms: " + error);
            else if (bytes == 0 && code < 300) log(true, "[PROXY] " + what + " body ended with 0 bytes (" + (eof ? "spider stream empty" : "player closed") + ") " + ms + "ms");
            else log(false, "[PROXY] " + what + " body " + bytes + "B " + (eof ? "complete" : "closed by player") + " " + ms + "ms");
        }
    }
}
