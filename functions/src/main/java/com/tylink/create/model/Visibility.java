package com.tylink.create.model;

import software.amazon.awssdk.utils.StringUtils;

import java.util.Optional;

public enum Visibility {
    PUBLIC, PRIVATE;

    /** Blank input defaults to PUBLIC; anything other than PUBLIC/PRIVATE is invalid (empty). */
    public static Optional<Visibility> parse(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Optional.of(PUBLIC);
        }
        try {
            return Optional.of(valueOf(raw.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
