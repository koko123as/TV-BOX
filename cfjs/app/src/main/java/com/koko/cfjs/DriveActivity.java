package com.koko.cfjs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 网盘登录 / 站点浏览: drives a spider in the foreground, the way FongMi's own pages do, so whatever login UI
 * the drive spider has can appear here (Runtime.activity() is this page while it is open):
 * - items carrying "action" call spider.action (upstream TypeFragment -> SiteApi.action), msg shown, web opened;
 * - other items open detailContent; vod_pic is drawn (config centres often put the login QR code there);
 * - episodes call playerContent and can be probed like SenPlayer would fetch them.
 * Also asks for the all-files permission upstream FongMi asks for (catvod Path.root = shared storage).
 */
public class DriveActivity extends Activity {

    private static final long LONG_MS = 180_000;
    private static final String PREF_SITE = "drive_site";
    private final ExecutorService calls = Executors.newCachedThreadPool();
    private final ExecutorService images = Executors.newFixedThreadPool(3);
    private TextView status;
    private LinearLayout classes;
    private LinearLayout list;
    private String siteKey = "";
    private String lastTid = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(10);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        status = new TextView(this);
        status.setTextIsSelectable(true);
        root.addView(status);
        TextView hint = new TextView(this);
        hint.setText("用法：先授予存储权限 → 选择「配置中心 / 我的网盘」类站点 → 点里面的夸克、UC、阿里、115、百度、天翼、123 等条目，按 Spider 的提示扫码或填 Cookie。"
                + "登录弹窗只会在本页打开时出现。登录后用「网盘自检」验证，再去 SenPlayer 播放。");
        root.addView(hint);
        LinearLayout row = new LinearLayout(this);
        row.addView(button("存储权限", this::requestStorage));
        row.addView(button("选择站点", this::chooseSite));
        row.addView(button("首页", this::loadHome));
        row.addView(button("网盘自检", this::driveTest));
        HorizontalScrollView rowScroll = new HorizontalScrollView(this);
        rowScroll.addView(row);
        root.addView(rowScroll);
        classes = new LinearLayout(this);
        HorizontalScrollView classScroll = new HorizontalScrollView(this);
        classScroll.addView(classes);
        root.addView(classScroll);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        siteKey = getSharedPreferences("cfjs", Context.MODE_PRIVATE).getString(PREF_SITE, "");
        if (siteKey.isEmpty() || Engine.get().site(siteKey) == null) siteKey = defaultSite();
        NetDisk.logStorage("网盘登录页");
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onDestroy() {
        calls.shutdown();
        images.shutdownNow();
        super.onDestroy();
    }

    private void render() {
        SiteInfo site = Engine.get().site(siteKey);
        status.setText(NetDisk.storageStatus() + "\n站点：" + (site == null ? (Engine.get().loaded() ? "未选择" : "配置未加载，请先在主页加载配置") : site.name + "（" + site.key + " / " + site.api + "）")
                + "\n服务：" + (JellyfinServer.running() ? "9978 运行中" : "未启动（播放测试需要先在主页启动服务）"));
    }

    private static String defaultSite() {
        for (SiteInfo site : Engine.get().sites()) if (site.supported() && NetDisk.isLoginSite(site)) return site.key;
        return "";
    }

    // ---------- storage permission (upstream PermissionUtil.requestFile) ----------

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (NetDisk.allFilesGranted()) {
                toast("已授予所有文件访问权限");
                return;
            }
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"}, 2);
        }
    }

    // ---------- site / home / category ----------

    private void chooseSite() {
        List<SiteInfo> sites = new ArrayList<>();
        for (SiteInfo site : Engine.get().sites()) if (site.supported() && NetDisk.isLoginSite(site)) sites.add(site);
        for (SiteInfo site : Engine.get().sites()) if (site.supported() && !NetDisk.isLoginSite(site)) sites.add(site);
        if (sites.isEmpty()) {
            toast("请先在主页加载配置");
            return;
        }
        String[] names = new String[sites.size()];
        for (int i = 0; i < sites.size(); i++) names[i] = (NetDisk.isLoginSite(sites.get(i)) ? "★ " : "") + sites.get(i).name + "  " + sites.get(i).api;
        new AlertDialog.Builder(this).setTitle("选择站点（★ = 配置/登录类）").setItems(names, (d, which) -> {
            siteKey = sites.get(which).key;
            getSharedPreferences("cfjs", Context.MODE_PRIVATE).edit().putString(PREF_SITE, siteKey).apply();
            render();
            loadHome();
        }).show();
    }

    private void loadHome() {
        if (Engine.get().site(siteKey) == null) {
            toast("先选择站点");
            return;
        }
        String key = siteKey;
        busy("加载首页…");
        NetDisk.i("ui home " + key);
        calls.execute(() -> {
            try {
                JsonObject home = SpiderBridge.get().home(key, LONG_MS).value;
                runOnUiThread(() -> showHome(key, home));
            } catch (Exception e) {
                fail("首页", e);
            }
        });
    }

    private void showHome(String key, JsonObject home) {
        if (isDestroyed()) return;
        classes.removeAllViews();
        for (JsonElement element : SpiderBridge.array(home, "class")) {
            if (!element.isJsonObject()) continue;
            String tid = Json.safeString(element.getAsJsonObject(), "type_id");
            String name = Json.safeString(element.getAsJsonObject(), "type_name");
            classes.addView(button(name.isEmpty() ? tid : name, () -> loadCategory(key, tid, name)));
        }
        JsonArray items = SpiderBridge.array(home, "list");
        NetDisk.i("ui home " + key + " class=" + classes.getChildCount() + " list=" + items.size());
        showList(key, items, items.size() == 0 ? "首页没有推荐，点上面的分类" : "首页推荐");
    }

    private void loadCategory(String key, String tid, String name) {
        lastTid = tid;
        busy("加载分类 " + name + "…");
        NetDisk.i("ui category " + key + " " + name);
        calls.execute(() -> {
            try {
                JsonObject result = SpiderBridge.get().category(key, tid, 1, new HashMap<>(), LONG_MS).value;
                JsonArray items = SpiderBridge.array(result, "list");
                NetDisk.i("ui category " + name + " list=" + items.size() + (items.size() == 0 ? " raw=" + NetDisk.cut(result.toString(), 300) : ""));
                runOnUiThread(() -> showList(key, items, name));
            } catch (Exception e) {
                fail("分类", e);
            }
        });
    }

    private void showList(String key, JsonArray items, String title) {
        if (isDestroyed()) return;
        list.removeAllViews();
        list.addView(label(title + "（" + items.size() + "）", true));
        int shown = 0;
        for (JsonElement element : items) {
            if (!element.isJsonObject() || shown++ >= 100) continue;
            JsonObject vod = element.getAsJsonObject();
            String id = Json.safeString(vod, "vod_id");
            String name = Json.safeString(vod, "vod_name");
            String action = Json.safeString(vod, "action");
            String tag = Json.safeString(vod, "vod_tag");
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(image, new LinearLayout.LayoutParams(dp(90), dp(110)));
            TextView text = new TextView(this);
            text.setPadding(dp(8), 0, 0, 0);
            text.setText(name + "\n" + Json.safeString(vod, "vod_remarks") + (action.isEmpty() ? "" : "\n[操作]") + ("folder".equals(tag) ? "\n[文件夹]" : ""));
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.setOnClickListener(v -> {
                if (!action.isEmpty()) runAction(key, name, action);
                else if ("folder".equals(tag)) loadCategory(key, id, name);
                else loadDetail(key, id, name);
            });
            list.addView(row);
            loadImage(image, Json.safeString(vod, "vod_pic"));
        }
    }

    // ---------- action / detail / play ----------

    private void runAction(String key, String name, String action) {
        busy("执行 " + name + "…（如出现二维码/输入框，请按提示操作）");
        NetDisk.i("ui action " + key + " " + name);
        calls.execute(() -> {
            try {
                JsonObject result = SpiderBridge.get().action(key, action, LONG_MS).value;
                String msg = Json.safeString(result, "msg");
                JsonElement web = result.get("web");
                String url = web != null && web.isJsonObject() ? Json.safeString(web.getAsJsonObject(), "url") : "";
                NetDisk.i("ui action " + name + " -> msg=" + NetDisk.cut(msg, 200) + (url.isEmpty() ? "" : " web=" + Uri.parse(url).getHost()) + " keys=" + result.keySet());
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (!msg.isEmpty()) toast(msg);
                    if (!url.isEmpty()) WebLoginActivity.open(this, url);
                    status.append("\n" + name + "：" + (msg.isEmpty() ? "已执行" : msg));
                    if (!lastTid.isEmpty()) loadCategory(key, lastTid, "刷新");
                });
            } catch (Exception e) {
                fail("操作", e);
            }
        });
    }

    private void loadDetail(String key, String id, String name) {
        busy("打开 " + name + "…（如出现二维码，请按提示扫码）");
        NetDisk.i("ui detail " + key + " " + name);
        calls.execute(() -> {
            try {
                JsonObject result = SpiderBridge.get().detail(key, id, LONG_MS).value;
                JsonArray items = SpiderBridge.array(result, "list");
                NetDisk.i("ui detail " + name + " list=" + items.size() + (items.size() == 0 ? " raw=" + NetDisk.cut(result.toString(), 300) : ""));
                runOnUiThread(() -> showDetail(key, name, items.size() > 0 && items.get(0).isJsonObject() ? items.get(0).getAsJsonObject() : null));
            } catch (Exception e) {
                fail("详情", e);
            }
        });
    }

    private void showDetail(String key, String name, JsonObject vod) {
        if (isDestroyed()) return;
        list.removeAllViews();
        if (vod == null) {
            list.addView(label(name + "：详情为空（看日志 [NETDISK] 行）", true));
            return;
        }
        list.addView(label(Json.safeString(vod, "vod_name").isEmpty() ? name : Json.safeString(vod, "vod_name"), true));
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        list.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        loadImage(image, Json.safeString(vod, "vod_pic"));
        TextView content = label(Json.safeString(vod, "vod_remarks") + "\n" + Json.safeString(vod, "vod_content"), false);
        content.setTextIsSelectable(true);
        list.addView(content);
        JsonArray flags = vod.has("cfjs_flags") && vod.get("cfjs_flags").isJsonArray() ? vod.getAsJsonArray("cfjs_flags") : new JsonArray();
        NetDisk.i("ui detail lines: " + NetDisk.cut(Json.safeString(vod, "vod_play_from"), 300));
        for (JsonElement element : flags) {
            JsonObject flag = element.getAsJsonObject();
            String flagName = Json.safeString(flag, "flag");
            String drive = NetDisk.driveOf(flagName);
            list.addView(label("线路 " + flagName + (drive.isEmpty() ? "" : "（" + drive + "）"), true));
            LinearLayout episodes = new LinearLayout(this);
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.addView(episodes);
            int count = 0;
            for (JsonElement item : flag.getAsJsonArray("episodes")) {
                if (count++ >= 40) break;
                String epName = Json.safeString(item.getAsJsonObject(), "name");
                String epId = Json.safeString(item.getAsJsonObject(), "id");
                episodes.addView(button(epName, () -> play(key, flagName, epId, epName)));
            }
            list.addView(scroll);
        }
    }

    private void play(String key, String flag, String id, String name) {
        busy("获取播放地址 " + flag + " " + name + "…");
        calls.execute(() -> {
            try {
                JsonObject p = SpiderBridge.get().play(key, flag, id, LONG_MS).value;
                String url = Json.safeString(p, "url");
                JsonElement raw = p.get("raw");
                JsonObject other = raw != null && raw.isJsonObject() ? raw.getAsJsonObject().deepCopy() : new JsonObject();
                other.remove("header");
                other.remove("url");
                String text = "url: " + NetDisk.cut(url, 300) + "\nheader: " + SelfTest.headerKeys(p.get("header")) + "\nparse=" + Json.safeString(p, "parse") + " jx=" + Json.safeString(p, "jx")
                        + " 直连=" + Json.safeString(p, "directPlayable") + (p.has("notDirectReason") ? "（" + Json.safeString(p, "notDirectReason") + "）" : "") + "\n其他: " + NetDisk.cut(other.toString(), 300);
                NetDisk.i("ui play " + flag + " " + name + " -> " + text.replace('\n', ' '));
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    status.append("\n播放 " + flag + "：" + (url.isEmpty() ? "没有地址" : "已获取"));
                    AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(flag + " " + name).setMessage(text).setNegativeButton("关闭", null);
                    if (!url.isEmpty()) builder.setPositiveButton("测试地址", (d, w) -> probe(url, p.get("header")));
                    builder.show();
                });
            } catch (Exception e) {
                fail("播放", e);
            }
        });
    }

    private void probe(String url, JsonElement header) {
        busy("测试地址（读取前 64KB）…");
        calls.execute(() -> {
            String result = DriveTest.probe(url, header);
            NetDisk.i("ui " + result.replace('\n', ' '));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                new AlertDialog.Builder(this).setTitle("测试结果").setMessage(result).setPositiveButton("确定", null).show();
                render();
            });
        });
    }

    private void driveTest() {
        if (DriveTest.running()) {
            toast("网盘自检正在运行");
            return;
        }
        if (!JellyfinServer.running()) SpiderService.start(this);
        SiteInfo current = Engine.get().site(siteKey);
        String key = current != null && !NetDisk.isLoginSite(current) ? siteKey : "";
        DriveTest.start(key);
        toast("网盘自检开始（保持本页打开），结果写入「下载」目录和下方日志");
    }

    // ---------- images ----------

    private void loadImage(ImageView view, String pic) {
        if (pic == null || pic.isEmpty()) return;
        String url = pic.contains("@") && (pic.contains("@Referer=") || pic.contains("@User-Agent=") || pic.contains("@Cookie=")) ? pic.substring(0, pic.indexOf('@')) : pic;
        if (url.startsWith("//")) url = "https:" + url;
        if (url.startsWith("proxy://")) url = JellyfinServer.BASE + "/proxy?" + url.substring(8);
        String target = url;
        images.execute(() -> {
            Bitmap bitmap = target.startsWith("data:image/") ? CfjsDialog.dataImage(target) : fetch(target);
            if (bitmap != null) runOnUiThread(() -> {
                if (!isDestroyed()) view.setImageBitmap(bitmap);
            });
        });
    }

    private static Bitmap fetch(String url) {
        if (!url.startsWith("http")) return null;
        try (Response response = OkHttp.client(20_000).newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            byte[] bytes = response.body().bytes();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 600 && bounds.outHeight / (sample * 2) >= 600) sample *= 2;
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- small helpers ----------

    private void busy(String text) {
        status.setText(text);
    }

    private void fail(String what, Exception e) {
        String text = e instanceof BridgeException b ? b.code + " " + b.getMessage() : String.valueOf(e);
        NetDisk.w("ui " + what + " failed: " + text);
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            render();
            status.append("\n" + what + "失败：" + text);
            toast(what + "失败：" + text);
        });
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private TextView label(String text, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setPadding(0, dp(6), 0, dp(2));
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
