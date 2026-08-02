package com.tylink.features.decode;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DecodeUrlHandlerTest {

    private final DecodeUrlHandler handler = new DecodeUrlHandler();

    @Test
    void returnsRedirectWithLocationHeader() {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withPathParameters(Map.of("shortCode", "abc123"))
                .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(302, response.getStatusCode());
        assertNotNull(response.getHeaders().get("Location"));
    }

    @Test
    void handlesMissingPathParametersWithoutThrowing() {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder().build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(302, response.getStatusCode());
    }
}
