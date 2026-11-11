package com.koko.cfjs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * 收藏与观看进度（files/jf-user.json），供 SenPlayer 的「收藏」「继续观看」「下一集」使用。
 * Ported from SpiderHost UserStore: only what SenPlayer reports through the standard Jellyfin endpoints is stored,
 * each record with a snapshot (name, poster, ids) so Home rows need no site call.
 */
final class JfUserStore {

    static final class Snap {
        final String id, kind, name, pic, year, seriesId, seriesName, seasonId, seasonName;
        final int index, parentIndex;

        Snap(String id, String kind, String name, String pic, String year) {
            this(id, kind, name, pic, year, "", "", "", "", 0, 0);
        }

        Snap(String id, String kind, String name, String pic, String year, String seriesId, String seriesName, String seasonId, String seasonName, int index, int parentIndex) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.pic = pic;
            this.year = year;
            this.seriesId = seriesId;
            this.seriesName = seriesName;
            this.seasonId = seasonId;
            this.seasonName = seasonName;
            this.index = index;
            this.parentIndex = parentIndex;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("kind", kind).put("name", name).put("pic", pic).put("year", year)
                    .put("seriesId", seriesId).put("seriesName", seriesName).put("seasonId", seasonId).put("seasonName", seasonName)
                    .put("index", index).put("parentIndex", parentIndex);
        }

        static Snap of(JSONObject o) {
            return new Snap(o.optString("id"), o.optString("kind"), o.optString("name"), o.optString("pic"), o.optString("year"),
                    o.optString("seriesId"), o.optString("seriesName"), o.optString("seasonId"), o.optString("seasonName"),
                    o.optInt("index"), o.optInt("parentIndex"));
        }
    }

    static final class Fav {
        final Snap snap;
        final long at;

        Fav(Snap snap, long at) {
            this.snap = snap;
            this.at = at;
        }
    }

    static final class Prog {
        final Snap snap;
        final long ticks, runtime, at;
        final boolean played;

        Prog(Snap snap, long ticks, long runtime, boolean played, long at) {
            this.snap = snap;
            this.ticks = ticks;
            this.runtime = runtime;
            this.played = played;
            this.at = at;
        }
    }

    private static final int MAX_PROGRESS = 300;
    private static final Object LOCK = new Object();
    private static File file;
    private static final LinkedHashMap<String, Fav> FAVS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Prog> PROGS = new LinkedHashMap<>();

    private JfUserStore() {
    }

    static void init(File dir) {
        synchronized (LOCK) {
            if (file != null) return;
            file = new File(dir, "jf-user.json");
            if (!file.exists()) return;
            try {
                JSONObject o = new JSONObject(JellyfinApi.readText(file));
                JSONArray a = o.optJSONArray("favorites");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject it = a.getJSONObject(i);
                    FAVS.put(it.getString("id"), new Fav(Snap.of(it.getJSONObject("snap")), it.optLong("at")));
                }
                a = o.optJSONArray("progress");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject it = a.getJSONObject(i);
                    PROGS.put(it.getString("id"), new Prog(Snap.of(it.getJSONObject("snap")), it.optLong("ticks"), it.optLong("runtime"), it.optBoolean("played"), it.optLong("at")));
                }
            } catch (Exception e) {
                BridgeLog.w("[JF] USER 读取 jf-user.json 失败: " + e);
            }
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            JSONArray favs = new JSONArray();
            for (Fav f : FAVS.values()) favs.put(new JSONObject().put("id", f.snap.id).put("at", f.at).put("snap", f.snap.toJson()));
            JSONArray progs = new JSONArray();
            for (Prog p : PROGS.values()) progs.put(new JSONObject().put("id", p.snap.id).put("ticks", p.ticks).put("runtime", p.runtime).put("played", p.played).put("at", p.at).put("snap", p.snap.toJson()));
            String text = new JSONObject().put("favorites", favs).put("progress", progs).toString();
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) BridgeLog.w("[JF] USER 写入失败: rename");
        } catch (Exception e) {
            BridgeLog.w("[JF] USER 写入失败: " + e);
        }
    }

    static boolean isFavorite(String id) {
        synchronized (LOCK) {
            return FAVS.containsKey(id);
        }
    }

    static void setFavorite(Snap snap, boolean on) {
        synchronized (LOCK) {
            if (on) FAVS.put(snap.id, new Fav(snap, System.currentTimeMillis()));
            else FAVS.remove(snap.id);
            save();
        }
        BridgeLog.i("[JF] USER " + (on ? "收藏 " : "取消收藏 ") + snap.kind + " " + snap.name);
    }

    /** Newest first. */
    static List<Fav> favorites() {
        synchronized (LOCK) {
            List<Fav> list = new ArrayList<>(FAVS.values());
            list.sort(Comparator.comparingLong((Fav f) -> f.at).reversed());
            return list;
        }
    }

    static Prog progress(String id) {
        synchronized (LOCK) {
            return PROGS.get(id);
        }
    }

    static void report(Snap snap, Long ticks, Long runtime, Boolean played) {
        synchronized (LOCK) {
            Prog old = PROGS.get(snap.id);
            long rt = runtime != null && runtime > 0 ? runtime : old != null ? old.runtime : 0L;
            long pos = ticks != null ? ticks : old != null ? old.ticks : 0L;
            // Past 90% counts as watched, like Jellyfin's default MaxResumePct.
            boolean done = played != null ? played : ((rt > 0 && pos >= rt * 9 / 10) || (old != null && old.played && ticks == null));
            PROGS.remove(snap.id);
            PROGS.put(snap.id, new Prog(snap, done ? 0L : pos, rt, done, System.currentTimeMillis()));
            Iterator<String> it = PROGS.keySet().iterator();
            while (PROGS.size() > MAX_PROGRESS && it.hasNext()) {
                it.next();
                it.remove();
            }
            save();
        }
    }

    private static List<Prog> newest() {
        List<Prog> list = new ArrayList<>(PROGS.values());
        list.sort(Comparator.comparingLong((Prog p) -> p.at).reversed());
        return list;
    }

    /** Unfinished episodes, newest first, one per series. */
    static List<Prog> resume() {
        synchronized (LOCK) {
            List<Prog> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Prog p : newest()) if (!p.played && p.ticks > 0 && seen.add(p.snap.seriesId)) out.add(p);
            return out;
        }
    }

    /** Last record per series, newest first, kept when it is finished: candidates for 下一集. */
    static List<Prog> lastWatched() {
        synchronized (LOCK) {
            List<Prog> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Prog p : newest()) if (seen.add(p.snap.seriesId) && p.played) out.add(p);
            return out;
        }
    }
}
