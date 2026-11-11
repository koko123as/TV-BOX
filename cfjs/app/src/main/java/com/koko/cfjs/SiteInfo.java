package com.koko.cfjs;

import com.github.catvod.utils.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One entry of the config's {@code sites}, read with the same rules as upstream {@code Site}:
 * ext may be a string, object or array (ExtAdapter); jar falls back to the global spider;
 * searchable defaults to 1; header is read with {@code Json.toMap} (HeaderAdapter).
 */
public final class SiteInfo {

    public final String key;
    public final String name;
    public final String api;
    public final int type;
    public final String ext;
    public final String jar;
    public final boolean searchable;
    public final List<String> categories;
    public final Map<String, String> header;

    private SiteInfo(JsonObject o, String spider) {
        key = Json.safeString(o, "key");
        name = Json.safeString(o, "name");
        api = Json.safeString(o, "api");
        type = integer(o, "type", 0);
        ext = ext(o.get("ext"));
        String own = Json.safeString(o, "jar");
        jar = own.isEmpty() ? spider : own;
        searchable = integer(o, "searchable", 1) == 1;
        categories = o.has("categories") ? Json.safeListString(o, "categories") : Collections.emptyList();
        header = o.has("header") ? Json.toMap(o.get("header")) : new HashMap<>();
    }

    private static int integer(JsonObject o, String key, int fallback) {
        try {
            JsonElement value = o.get(key);
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static SiteInfo from(JsonElement element, String spider) {
        if (element == null || !element.isJsonObject()) return null;
        SiteInfo site = new SiteInfo(element.getAsJsonObject(), spider);
        return site.key.isEmpty() ? null : site;
    }

    private static String ext(JsonElement json) {
        if (json == null || json.isJsonNull()) return "";
        if (json.isJsonPrimitive()) return json.getAsJsonPrimitive().getAsString();
        return json.toString();
    }

    /** Runtime kind as upstream BaseLoader decides it. */
    public String kind() {
        if (type != 3) return "type" + type;
        if (api.startsWith("node:")) return "node";
        if (api.contains(".py")) return "python";
        if (api.contains(".js")) return "quickjs";
        if (api.startsWith("csp_")) return "java";
        return "unknown";
    }

    /** v1 runs Java spiders only. */
    public boolean supported() {
        return "java".equals(kind());
    }
}
