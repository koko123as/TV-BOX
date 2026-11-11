package com.koko.cfjs;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Minimal page for a spider action result that asks to open a web page (upstream Result "web" -> WebActivity),
 * e.g. a drive's own web login. Cookies land in the system CookieManager, which spider.net reads through
 * JsHost (same as upstream). Only cookie names are logged, never values.
 */
public class WebLoginActivity extends Activity {

    private static final String EXTRA_URL = "url";
    private WebView web;
    private String url;

    static void open(Activity from, String url) {
        if (from == null || url == null || !url.startsWith("http")) return;
        from.startActivity(new Intent(from, WebLoginActivity.class).putExtra(EXTRA_URL, url));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        url = getIntent().getStringExtra(EXTRA_URL);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("网页登录：登录完成后点「完成」");
        root.addView(title);
        Button done = new Button(this);
        done.setText("完成");
        done.setOnClickListener(v -> finish());
        root.addView(done);
        try {
            web = new WebView(this);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
            web.setWebViewClient(new WebViewClient());
            root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            web.loadUrl(url);
            NetDisk.i("web login open " + host(url));
        } catch (Throwable e) {
            NetDisk.w("web login: 系统 WebView 不可用 " + e);
            TextView error = new TextView(this);
            error.setText("系统 WebView 不可用：" + e);
            root.addView(error);
        }
        setContentView(root);
    }

    private static String host(String url) {
        Uri uri = Uri.parse(url);
        return uri.getHost() == null ? url : uri.getHost();
    }

    @Override
    protected void onDestroy() {
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            int count = cookie == null || cookie.isEmpty() ? 0 : cookie.split(";").length;
            NetDisk.i("web login closed " + host(url) + " cookies=" + count);
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
