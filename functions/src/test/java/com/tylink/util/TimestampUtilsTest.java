package com.tylink.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimestampUtilsTest {

    @Test
    void nowIsFixedWidthNanosecondPrecisionAndParseable() {
        String timestamp = TimestampUtils.now();

        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{9}Z"));
        assertDoesNotThrow(() -> Instant.parse(timestamp));
    }

    @Test
    void consecutiveTimestampsAreLexicographicallySortable() {
        String first = TimestampUtils.now();
        String second = TimestampUtils.now();

        assertTrue(first.compareTo(second) <= 0);
    }
}
