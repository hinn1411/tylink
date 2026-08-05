package com.tylink.repository;

public class UrlRepositoryException extends RuntimeException {
    public UrlRepositoryException(String message) {
        super(message);
    }

    public UrlRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
