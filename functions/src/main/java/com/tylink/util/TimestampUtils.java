package com.tylink.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class TimestampUtils {

    // Fixed-width fraction (unlike Instant.toString()'s trimmed 0/3/6/9-digit fraction), so
    // timestamps built from this remain lexicographically sortable when used as DynamoDB sort
    // key components.
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);

    private TimestampUtils() {
    }

    public static String now() {
        return FORMATTER.format(Instant.now());
    }
}
