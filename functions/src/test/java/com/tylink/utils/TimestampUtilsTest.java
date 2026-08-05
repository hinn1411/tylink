package com.tylink.utils;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimestampUtilsTest {

    @Test
    void now_called_isFixedWidthNanosecondPrecisionAndParseable() {
        String timestamp = TimestampUtils.now();

        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{9}Z"));
        assertDoesNotThrow(() -> Instant.parse(timestamp));
    }

    @Test
    void now_calledConsecutively_producesLexicographicallySortableTimestamps() {
        String first = TimestampUtils.now();
        String second = TimestampUtils.now();

        assertTrue(first.compareTo(second) <= 0);
    }
}
