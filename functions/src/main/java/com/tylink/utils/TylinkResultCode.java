package com.tylink.utils;

/**
 * Numeric result codes returned in the {@code code} field of every response body, separate from
 * the HTTP status. {@code SUCCESS} covers every successful/expected outcome across all handlers;
 * every other constant is a unique 6xx+ code retired for good once assigned, never reused for a
 * different scenario — see docs/technical_decisions/12-result-codes.md.
 *
 * <p>Exception: {@code REDIRECT_NOT_FOUND}, {@code DELETE_NOT_FOUND}, and {@code UPDATE_NOT_FOUND}
 * each deliberately cover two different internal causes (a short code that doesn't exist, and one
 * that exists but is private and not owned by the caller) to avoid leaking that distinction to
 * the client.
 */
public enum TylinkResultCode {

    SUCCESS(0, 0, ""),

    SHORTEN_INVALID_REQUEST_BODY(600, 400, "invalid request body"),
    SHORTEN_LONG_URL_REQUIRED(601, 400, "longUrl is required"),
    SHORTEN_IDEMPOTENCY_KEY_REQUIRED(602, 400, "Idempotency-Key header is required"),
    SHORTEN_INVALID_LONG_URL(603, 400, "longUrl must be a valid http or https URL"),
    SHORTEN_INVALID_VISIBILITY(604, 400, "visibility must be PUBLIC or PRIVATE"),
    SHORTEN_PRIVATE_CREATE_UNAUTHORIZED(605, 401, "unauthorized"),
    SHORTEN_IDEMPOTENCY_KEY_REUSED(606, 409, "Idempotency-Key already used with a different request"),
    SHORTEN_IDEMPOTENCY_IN_PROGRESS(607, 409, "a request with this Idempotency-Key is already being processed"),
    SHORTEN_FAILED(608, 500, "failed to create short url"),

    REDIRECT_NOT_FOUND(610, 404, "Short url not found"),
    REDIRECT_LOOKUP_FAILED(611, 500, "failed to look up short url"),
    REDIRECT_GONE(612, 410, "short url no longer available"),

    LIST_INVALID_LIMIT(620, 400, "limit must be an integer between 1 and 100"),
    LIST_INVALID_CURSOR(621, 400, "invalid cursor"),
    LIST_FAILED(622, 500, "failed to list urls"),

    DELETE_NOT_FOUND(630, 404, "Short url not found"),
    DELETE_FAILED(631, 500, "failed to delete short url"),

    UPDATE_NOT_FOUND(640, 404, "Short url not found"),
    UPDATE_LONG_URL_REQUIRED(641, 400, "longUrl is required"),
    UPDATE_INVALID_LONG_URL(642, 400, "longUrl must be a valid http or https URL"),
    UPDATE_FAILED(643, 500, "failed to update short url"),

    LOGIN_INVALID_REQUEST_BODY(650, 400, "invalid request body"),
    LOGIN_USERNAME_REQUIRED(651, 400, "username is required"),
    LOGIN_PASSWORD_REQUIRED(652, 400, "password is required"),
    LOGIN_INVALID_CREDENTIALS(653, 401, "invalid username or password"),
    LOGIN_FAILED(654, 500, "failed to authenticate");

    private final int code;
    private final int httpStatus;
    private final String message;

    TylinkResultCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
