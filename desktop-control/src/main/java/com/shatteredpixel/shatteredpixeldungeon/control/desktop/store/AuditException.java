package com.shatteredpixel.shatteredpixeldungeon.control.desktop.store;

/** The public message is safe; the cause belongs in the private exception log. */
public final class AuditException extends RuntimeException {
    public final String code;

    public AuditException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
