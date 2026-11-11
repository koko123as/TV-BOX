package com.koko.cfjs;

import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Splits vod_play_from / vod_play_url exactly like upstream Vod.setFlags and Flag.setEpisodes. */
final class Episodes {

    private Episodes() {
    }

    static JsonArray flags(JsonObject vod) {
        String[] playUrls = Json.safeString(vod, "vod_play_url").split("\\$\\$\\$");
        String[] playFlags = Json.safeString(vod, "vod_play_from").split("\\$\\$\\$");
        JsonArray flags = new JsonArray();
        for (int i = 0; i < playFlags.length; i++) {
            if (playFlags[i].trim().isEmpty() || i >= playUrls.length || playUrls[i].isEmpty()) continue;
            JsonObject flag = new JsonObject();
            flag.addProperty("flag", playFlags[i].trim());
            flag.add("episodes", episodes(playUrls[i]));
            flags.add(flag);
        }
        return flags;
    }

    private static JsonArray episodes(String url) {
        String[] urls = url.contains("#") ? url.split("#") : new String[]{url};
        JsonArray items = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < urls.length; i++) {
            String[] split = urls[i].split("\\$", 2);
            String number = String.format(Locale.ROOT, "%02d", i + 1);
            String name = split.length > 1 ? (split[0].isEmpty() ? number : split[0].trim()) : number;
            String id = split.length > 1 ? split[1] : urls[i];
            if (!seen.add(name + "\u0000" + id)) continue;
            JsonObject episode = new JsonObject();
            episode.addProperty("name", name);
            episode.addProperty("id", id);
            items.add(episode);
        }
        return items;
    }
}
