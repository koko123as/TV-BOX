package com.fongmi.android.tv.api.loader;

import com.github.catvod.net.Net;

/**
 * CFJS shim for upstream {@code JsHost}. Upstream implements the QuickJS host (WebView cookies, dialogs);
 * JarLoader only needs it as {@link Net.Cookies}. CFJS runs without WebView, so cookies are not shared.
 */
final class JsHost implements Net.Cookies {

    static final JsHost INSTANCE = new JsHost();

    private JsHost() {
    }

    @Override
    public String getCookie(String url) {
        return "";
    }

    @Override
    public boolean setCookie(String url, String cookie) {
        return false;
    }
}
