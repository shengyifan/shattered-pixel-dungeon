package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

/** A public, deliberately bounded protocol error. */
public final class ProtocolException extends RuntimeException {
    public final String code;

    public ProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }
}
