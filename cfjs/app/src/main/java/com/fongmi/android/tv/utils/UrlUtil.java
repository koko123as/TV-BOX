package com.fongmi.android.tv.utils;

/**
 * CFJS shim for upstream {@code UrlUtil.convert}. Upstream maps assets://, proxy:// and file:// onto its own
 * local Server; CFJS has no such server, so those schemes are left unchanged and report as unsupported later.
 */
public final class UrlUtil {

    private UrlUtil() {
    }

    public static String convert(String url) {
        return url == null ? "" : url;
    }
}
