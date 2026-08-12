package com.tylink.utils;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.utils.StringUtils;
import software.amazon.lambda.powertools.metrics.Metrics;
import software.amazon.lambda.powertools.metrics.MetricsFactory;
import software.amazon.lambda.powertools.metrics.model.DimensionSet;
import software.amazon.lambda.powertools.metrics.model.MetricUnit;

import java.util.Map;
import java.util.Optional;

public final class RequestUtils {

    private static final Logger log = LogManager.getLogger(RequestUtils.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Metrics metrics = MetricsFactory.getMetricsInstance();
    // AWS_LAMBDA_FUNCTION_NAME is only set by Lambda at runtime; "unknown"
    // is a fallback
    private static final String FUNCTION_NAME = Optional.ofNullable(System.getenv("AWS_LAMBDA_FUNCTION_NAME"))
            .orElse("unknown");

    private RequestUtils() {
    }

    public static APIGatewayV2HTTPResponse errorResponse(TylinkResultCode code) {
        emitResult(code);
        return jsonResponse(code.httpStatus(), Map.of("message", code.message(), "code", code.code()));
    }

    public static void recordSuccess(TylinkResultCode code) {
        emitResult(code);
    }

    public static APIGatewayV2HTTPResponse successResponse(int statusCode, Object body) {
        emitResult(TylinkResultCode.SUCCESS);
        return jsonResponse(statusCode, body);
    }

    private static void emitResult(TylinkResultCode code) {
        metrics.flushMetrics(m -> {
            m.addDimension(DimensionSet.of("FunctionName", FUNCTION_NAME, "ResultCode", code.name()));
            m.addMetric("RequestResult", 1, MetricUnit.COUNT);
        });
    }

    public static String getHeader(APIGatewayV2HTTPEvent input, String name) {
        Map<String, String> headers = input.getHeaders();
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    public static <T> T parseBody(String body, Class<T> type) {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        try {
            return MAPPER.readValue(body, type);
        } catch (JsonProcessingException e) {
            log.warn("Rejected request: malformed {} body", type.getSimpleName());
            return null;
        }
    }

    public static APIGatewayV2HTTPResponse jsonResponse(int statusCode, Map<String, String> body) {
        return jsonResponse(statusCode, (Object) body);
    }

    public static APIGatewayV2HTTPResponse jsonResponse(int statusCode, Object body) {
        try {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(statusCode)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(MAPPER.writeValueAsString(body))
                    .build();
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response body, falling back to 500", e);
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody("{\"message\":\"internal server error\"}")
                    .build();
        }
    }
}
