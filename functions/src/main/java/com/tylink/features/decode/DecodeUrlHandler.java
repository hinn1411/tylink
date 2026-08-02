package com.tylink.features.decode;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.lambda.powertools.logging.Logging;

import java.util.Map;

public class DecodeUrlHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LogManager.getLogger(DecodeUrlHandler.class);
    private static final String MOCK_DESTINATION_URL = "https://example.com/mock-destination";

    @Logging(logEvent = true)
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        String shortCode = input.getPathParameters() != null
                ? input.getPathParameters().get("shortCode")
                : null;
        log.info("Received decode request for shortCode={}", shortCode);

        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(302)
                .withHeaders(Map.of("Location", MOCK_DESTINATION_URL))
                .withBody("")
                .build();
    }
}
