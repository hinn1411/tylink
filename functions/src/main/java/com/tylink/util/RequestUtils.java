package com.tylink.util;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.utils.StringUtils;

import java.util.Map;

public final class RequestUtils {

    private static final Logger log = LogManager.getLogger(RequestUtils.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestUtils() {
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
