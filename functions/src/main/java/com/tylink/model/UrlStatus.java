package com.tylink.model;

public enum UrlStatus {
    ACTIVE, DELETED;

    public static UrlStatus parse(String raw) {
        try {
            return valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
