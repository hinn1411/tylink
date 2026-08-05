package com.tylink.models;

import software.amazon.awssdk.utils.StringUtils;

public enum Visibility {
    PUBLIC, PRIVATE;

    public static Visibility parse(String raw) {
        // Default is PUBLIC
        if (StringUtils.isBlank(raw)) {
            return PUBLIC;
        }
        try {
            return valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
