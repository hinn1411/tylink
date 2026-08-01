package com.tylink.create.model;

import software.amazon.awssdk.utils.StringUtils;

import java.util.Optional;

public enum Visibility {
    PUBLIC, PRIVATE;

    public static Optional<Visibility> parse(String raw) {
        // Default is PUBLIC
        if (StringUtils.isBlank(
            raw)) {
            return Optional.of(PUBLIC);
        }
        try {
            return Optional.of(valueOf(raw.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
