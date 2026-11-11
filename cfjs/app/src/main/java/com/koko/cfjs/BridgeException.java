package com.koko.cfjs;

/**
 * Error with a stable machine-readable {@link #code}: CONFIG_*, SITE_NOT_FOUND, UNSUPPORTED_SITE,
 * BAD_REQUEST, BUSY, TIMEOUT, CANCELLED, SPIDER_ERROR.
 */
public final class BridgeException extends Exception {

    public final String code;

    public BridgeException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BridgeException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
