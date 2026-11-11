package com.fongmi.android.tv.api.loader;

import android.webkit.CookieManager;

import com.github.catvod.net.Net;

/**
 * CFJS shim for upstream {@code JsHost}. JarLoader only needs it as {@link Net.Cookies} for {@code spider.net}.
 * Like upstream (ui/custom/WebViewCookies) the store is the system WebView {@link CookieManager}: persistent,
 * shared with the in-app login page (com.koko.cfjs.WebLoginActivity), so a cookie a drive spider saves through
 * spider.net survives restarts. If the phone has no WebView provider it degrades to "no cookies".
 */
final class JsHost implements Net.Cookies {

    static final JsHost INSTANCE = new JsHost();

    private JsHost() {
    }

    private static boolean http(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static boolean lineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    @Override
    public String getCookie(String url) {
        if (!http(url)) return "";
        try {
            String value = CookieManager.getInstance().getCookie(url);
            return value == null || lineBreak(value) ? "" : value;
        } catch (Throwable e) {
            return "";
        }
    }

    @Override
    public boolean setCookie(String url, String cookie) {
        if (!http(url) || cookie == null || cookie.isEmpty() || lineBreak(cookie)) return false;
        try {
            CookieManager manager = CookieManager.getInstance();
            manager.setAcceptCookie(true);
            manager.setCookie(url, cookie);
            manager.flush();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
