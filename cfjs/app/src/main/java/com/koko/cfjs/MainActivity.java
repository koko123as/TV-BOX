package com.koko.cfjs;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Management page: config, service, Jellyfin home site, tunnel, self-test, status and log. Plain views, no upstream UI. */
public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText config;
    private TextView status;
    private TextView log;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        status = new TextView(this);
        status.setTextIsSelectable(true);
        root.addView(status);
        TextView hint = new TextView(this);
        hint.setText("SenPlayer: 127.0.0.1 端口 9978（添加 Jellyfin 服务器，用户名任意，密码留空）");
        hint.setTextIsSelectable(true);
        root.addView(hint);
        config = new EditText(this);
        config.setHint("点播配置地址（单仓 JSON）");
        config.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        config.setSingleLine(true);
        config.setText(Prefs.configUrl());
        root.addView(config);
        root.addView(button("保存并加载配置", this::loadConfig));
        root.addView(button("启动服务（HTTP API + Jellyfin）", () -> SpiderService.start(this)));
        root.addView(button("停止服务（释放全部资源）", () -> SpiderService.stop(this)));
        root.addView(button("选择 Jellyfin 首页站点", this::chooseHome));
        root.addView(button("启动 Quick Tunnel", () -> {
            if (!SpiderService.isRunning()) SpiderService.start(this);
            TunnelManager.start();
        }));
        root.addView(button("停止 Tunnel", () -> new Thread(TunnelManager::stop).start()));
        root.addView(button("运行自检（转到后台执行）", () -> {
            if (SelfTest.running()) {
                toast("自检正在运行");
                return;
            }
            if (!SpiderService.isRunning()) SpiderService.start(this);
            SelfTest.start();
            toast("自检开始，结果写入「下载」目录");
            moveTaskToBack(true);
        }));
        root.addView(button("复制 Tunnel 地址和 Token", this::copy));
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(11);
        log.setTextIsSelectable(true);
        root.addView(log);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setOnClickListener(v -> {
            action.run();
            render();
        });
        return button;
    }

    private void loadConfig() {
        String url = config.getText().toString().trim();
        Prefs.configUrl(url);
        if (url.isEmpty()) {
            toast("请填写配置地址");
            return;
        }
        new Thread(() -> {
            try {
                Engine.get().load(url);
                runOnUiThread(() -> toast("配置已加载"));
            } catch (Exception e) {
                BridgeLog.w("config load failed: " + e.getMessage());
                runOnUiThread(() -> toast("加载失败：" + e.getMessage()));
            }
        }, "cfjs-config").start();
    }

    /** The site whose 推荐 and categories SenPlayer shows as libraries (like 影视仓's 首页数据源). */
    private void chooseHome() {
        java.util.List<SiteInfo> list = new java.util.ArrayList<>();
        for (SiteInfo site : Engine.get().sites()) if (site.supported()) list.add(site);
        if (list.isEmpty()) {
            toast("请先加载配置");
            return;
        }
        String current = JellyfinApi.homeKey();
        String[] names = new String[list.size()];
        int checked = -1;
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i).name.isEmpty() ? list.get(i).key : list.get(i).name;
            if (list.get(i).key.equals(current)) checked = i;
        }
        new android.app.AlertDialog.Builder(this).setTitle("Jellyfin 首页站点").setSingleChoiceItems(names, checked, (dialog, which) -> {
            Prefs.jellyfinHome(list.get(which).key);
            BridgeLog.i("[JF] 首页站点切换为 " + list.get(which).key + "（SenPlayer 里刷新媒体库即可看到）");
            dialog.dismiss();
            render();
        }).setNegativeButton("取消", null).show();
    }

    private void copy() {
        String url = TunnelManager.publicUrl();
        ClipboardManager manager = getSystemService(ClipboardManager.class);
        manager.setPrimaryClip(ClipData.newPlainText("cfjs", (url.isEmpty() ? "(no tunnel)" : url) + "\nX-CFJS-Token: " + Prefs.token()));
        toast(url.isEmpty() ? "Tunnel 还没有地址，只复制了 Token" : "已复制");
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void render() {
        String url = TunnelManager.publicUrl();
        int sites = Engine.get().sites().size();
        long java = Engine.get().sites().stream().filter(SiteInfo::supported).count();
        status.setText("服务：" + (SpiderService.isRunning() ? "运行中" : "已停止")
                + "\nHTTP API：" + (BridgeServer.running() ? "http://" + BridgeServer.HOST + ":" + BridgeServer.PORT : "已停止")
                + "\nJellyfin：" + (JellyfinServer.running() ? JellyfinServer.BASE + (Engine.get().loaded() ? "，首页站点 " + JellyfinApi.shortName(JellyfinApi.homeKey()) : "") : "已停止")
                + "\n配置：" + (Engine.get().loaded() ? sites + " 个站点，Java " + java : "未加载")
                + "\nTunnel：" + (TunnelManager.running() ? (url.isEmpty() ? "连接中…" : url) : "已停止")
                + "\ncloudflared：" + (TunnelManager.binary().exists() ? TunnelManager.buildInfo() : "未打包")
                + "\n自检：" + (SelfTest.running() ? "运行中" : "空闲"));
        log.setText(BridgeLog.dump());
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }
}
