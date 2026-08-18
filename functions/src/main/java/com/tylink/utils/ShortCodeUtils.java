package com.tylink.utils;

import software.amazon.awssdk.utils.StringUtils;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public final class ShortCodeUtils {

    private static final String BASE62_ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int SHORT_CODE_LENGTH = 7;
    private static final Set<Character> VALID_CHARS = buildValidCharsSet();

    private ShortCodeUtils() {
    }

    public static String generate() {
        SecureRandom random = new SecureRandom();
        return random.ints(SHORT_CODE_LENGTH, 0, BASE62_ALPHABET.length())
                .mapToObj(BASE62_ALPHABET::charAt)
                .map(String::valueOf)
                .collect(Collectors.joining());
    }

    public static boolean isValid(String shortCode) {
        if (StringUtils.isBlank(shortCode) || shortCode.length() != SHORT_CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < shortCode.length(); i++) {
            if (!VALID_CHARS.contains(shortCode.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Set<Character> buildValidCharsSet() {
        Set<Character> chars = new HashSet<>();
        for (char c : BASE62_ALPHABET.toCharArray()) {
            chars.add(c);
        }
        return chars;
    }
}
