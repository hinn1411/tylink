package com.tylink.shorten.util;

import java.security.SecureRandom;
import java.util.stream.Collectors;

public final class ShortUrlGenerator {

    private static final String BASE62_ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int SHORT_CODE_LENGTH = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ShortUrlGenerator() {
    }

    public static String generate() {
        return RANDOM.ints(SHORT_CODE_LENGTH, 0, BASE62_ALPHABET.length())
                .mapToObj(BASE62_ALPHABET::charAt)
                .map(String::valueOf)
                .collect(Collectors.joining());
    }
}
