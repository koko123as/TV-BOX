package com.koko.cfjs;

import android.net.Uri;

import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Map;

/**
 * Normalises a spider {@code playerContent} result without dropping anything: the raw result stays under
 * {@code raw}; {@code url}, every quality in {@code urls}, the merged {@code header}, parse, jx, playUrl,
 * format and flag are lifted out, and {@code directPlayable} says whether SenPlayer could open the URL itself.
 */
final class PlayInfo {

    private PlayInfo() {
    }

    static JsonObject from(JsonObject raw, String flag, SiteInfo site) {
        JsonObject out = new JsonObject();
        JsonArray urls = urls(raw.get("url"));
        int position = raw.has("url") && raw.get("url").isJsonObject() ? integer(raw.getAsJsonObject("url"), "position") : 0;
        String url = position < urls.size() ? Json.safeString(urls.get(position).getAsJsonObject(), "url") : "";
        Map<String, String> header = raw.has("header") ? Json.toMap(headerElement(raw.get("header"))) : null;
        if (header == null || header.isEmpty()) header = site.header;
        JsonObject headers = new JsonObject();
        for (Map.Entry<String, String> entry : header.entrySet()) headers.addProperty(entry.getKey(), entry.getValue());
        int parse = integer(raw, "parse");
        int jx = integer(raw, "jx");
        String playUrl = Json.safeString(raw, "playUrl");
        String format = Json.safeString(raw, "format");
        String own = Json.safeString(raw, "flag");
        out.addProperty("url", url);
        out.add("urls", urls);
        out.add("header", headers);
        out.addProperty("parse", parse);
        out.addProperty("jx", jx);
        out.addProperty("playUrl", playUrl);
        out.addProperty("format", format);
        out.addProperty("flag", own.isEmpty() ? flag : own);
        out.addProperty("type", type(url, format));
        String reason = reason(url, parse, jx, playUrl);
        out.addProperty("directPlayable", reason == null);
        if (reason != null) out.addProperty("notDirectReason", reason);
        out.add("raw", raw);
        return out;
    }

    /** Header may be an object or a JSON string; upstream HeaderAdapter reads both through Json.toMap. */
    private static JsonElement headerElement(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            try {
                return Json.parse(element.getAsString());
            } catch (RuntimeException e) {
                return new JsonObject();
            }
        }
        return element;
    }

    /** Same shapes as upstream UrlAdapter: string, [name, url, name, url...] or {values:[{n,v}], position}. */
    private static JsonArray urls(JsonElement element) {
        JsonArray out = new JsonArray();
        if (element == null || element.isJsonNull()) return out;
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i + 1 < array.size(); i += 2) out.add(value(array.get(i).getAsString(), array.get(i + 1).getAsString()));
        } else if (element.isJsonObject()) {
            JsonElement values = element.getAsJsonObject().get("values");
            if (values != null && values.isJsonArray()) {
                for (JsonElement item : values.getAsJsonArray()) {
                    if (item.isJsonObject()) out.add(value(Json.safeString(item.getAsJsonObject(), "n"), Json.safeString(item.getAsJsonObject(), "v")));
                }
            }
        } else if (element.isJsonPrimitive()) {
            out.add(value("", element.getAsString()));
        }
        return out;
    }

    private static JsonObject value(String name, String url) {
        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        object.addProperty("url", url);
        return object;
    }

    private static int integer(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static String reason(String url, int parse, int jx, String playUrl) {
        if (url == null || url.isEmpty()) return "empty url";
        if (parse == 1) return "parse=1 (needs sniffing)";
        if (jx == 1) return "jx=1 (needs parser)";
        if (!playUrl.isEmpty()) return "playUrl parser";
        if (url.startsWith("proxy://")) return "local spider proxy";
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return "scheme " + scheme;
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1")) return "local address";
        return null;
    }

    static String type(String url, String format) {
        String text = format.toLowerCase(Locale.ROOT);
        if (text.contains("mpegurl")) return "hls";
        if (text.contains("dash")) return "dash";
        String path = url == null ? null : Uri.parse(url).getPath();
        path = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8")) return "hls";
        if (path.endsWith(".mpd")) return "dash";
        if (path.endsWith(".mp4") || path.endsWith(".m4v")) return "mp4";
        if (path.endsWith(".flv")) return "flv";
        if (path.endsWith(".mkv")) return "mkv";
        return "unknown";
    }
}
