package com.tylink.utils;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestUtilsTest {

    @Test
    void errorResponse_shortenLongUrlRequired_returnsStatusFromCode() {
        APIGatewayV2HTTPResponse response = RequestUtils.errorResponse(TylinkResultCode.SHORTEN_LONG_URL_REQUIRED);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void errorResponse_shortenLongUrlRequired_bodyContainsMessageAndNumericCode() {
        APIGatewayV2HTTPResponse response = RequestUtils.errorResponse(TylinkResultCode.SHORTEN_LONG_URL_REQUIRED);

        assertTrue(response.getBody().contains("\"message\":\"longUrl is required\""));
        assertTrue(response.getBody().contains("\"code\":601"));
    }

    @Test
    void errorResponse_redirectNotFound_returnsCode610() {
        APIGatewayV2HTTPResponse response = RequestUtils.errorResponse(TylinkResultCode.REDIRECT_NOT_FOUND);

        assertTrue(response.getBody().contains("\"code\":610"));
    }

    @Test
    void recordSuccess_success_doesNotThrow() {
        assertDoesNotThrow(() -> RequestUtils.recordSuccess(TylinkResultCode.SUCCESS));
    }

    @Test
    void successResponse_created_returnsGivenStatusCode() {
        APIGatewayV2HTTPResponse response = RequestUtils.successResponse(201, Map.of("shortCode", "abc123"));

        assertEquals(201, response.getStatusCode());
    }

    @Test
    void successResponse_created_bodyContainsGivenFields() {
        APIGatewayV2HTTPResponse response = RequestUtils.successResponse(201, Map.of("shortCode", "abc123"));

        assertTrue(response.getBody().contains("\"shortCode\":\"abc123\""));
    }
}
