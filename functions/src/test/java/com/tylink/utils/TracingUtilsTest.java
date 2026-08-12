package com.tylink.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TracingUtilsTest {

    @Test
    void parseRootTraceId_validHeader_returnsRootValue() {
        String traceId = TracingUtils.parseRootTraceId(
                "Root=1-5e1b4151-5ac6c58f7fdb9e12e6a3f8b6;Parent=53995c3f42cd8ad8;Sampled=1");

        assertEquals("1-5e1b4151-5ac6c58f7fdb9e12e6a3f8b6", traceId);
    }

    @Test
    void parseRootTraceId_headerMissingRoot_returnsNull() {
        String traceId = TracingUtils.parseRootTraceId("Sampled=1");

        assertNull(traceId);
    }

    @Test
    void parseRootTraceId_nullHeader_returnsNull() {
        String traceId = TracingUtils.parseRootTraceId(null);

        assertNull(traceId);
    }
}
