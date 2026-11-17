package io.fluxzero.tools.account;

/** A customer-safe failure; protocol bodies and credentials must never be included. */
public final class AccountException extends RuntimeException {
    private final String code;

    public AccountException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
