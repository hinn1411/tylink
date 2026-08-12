package com.tylink.utils;

import com.amazonaws.xray.interceptors.TracingInterceptor;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;

public final class TracingUtils {

    private TracingUtils() {
    }

    public static ClientOverrideConfiguration xrayOverrideConfiguration() {
        return ClientOverrideConfiguration.builder()
                .addExecutionInterceptor(new TracingInterceptor())
                .build();
    }

    public static String currentTraceId() {
        // Generated at runtime if tracing is enabled
        String traceId = System.getenv("_X_AMZN_TRACE_ID");
        return parseRootTraceId(traceId);
    }

    static String parseRootTraceId(String traceHeader) {
        if (traceHeader == null) {
            return null;
        }
        for (String part : traceHeader.split(";")) {
            if (part.startsWith("Root=")) {
                return part.substring("Root=".length());
            }
        }
        return null;
    }
}
