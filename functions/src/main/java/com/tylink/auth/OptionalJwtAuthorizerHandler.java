package com.tylink.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2CustomAuthorizerEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.lambda.powertools.logging.Logging;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * This handler never rejects a request, it only extracts identify from JWT.
 * When the Authorization header carries a valid Cognito token, its verified
 * {@code sub} is passed through as {@code ownerId} in the authorizer context; otherwise the
 * context is empty and downstream handlers treat the caller as anonymous.
 */
public class OptionalJwtAuthorizerHandler implements RequestHandler<APIGatewayV2CustomAuthorizerEvent, Map<String, Object>> {

    private static final Logger log = LogManager.getLogger(OptionalJwtAuthorizerHandler.class);

    private final CognitoJwtVerifier verifier;

    public OptionalJwtAuthorizerHandler() {
        this(new CognitoJwtVerifier(
                System.getenv("AWS_REGION"),
                System.getenv("USER_POOL_ID"),
                System.getenv("USER_POOL_CLIENT_ID")));
    }

    OptionalJwtAuthorizerHandler(CognitoJwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Logging
    @Override
    public Map<String, Object> handleRequest(APIGatewayV2CustomAuthorizerEvent input, Context context) {
        Optional<String> ownerId = verifier.verify(getRawJwtToken(input));

        log.info("owner is {}", ownerId.orElse("anonymous"));

        Map<String, Object> response = new HashMap<>();
        /* Must hardcore isAuthorized = true because we use EnableSimpleResponses.
        Otherwise, Gateway will reject it */
        response.put("isAuthorized", true);
        Map<String, String> authContext = new HashMap<>();
        authContext.put("ownerId", ownerId.orElse(""));
        response.put("context", authContext);
        return response;
    }

    private String getRawJwtToken(APIGatewayV2CustomAuthorizerEvent input) {
        if (input.getHeaders() == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : input.getHeaders().entrySet()) {
            if (entry.getKey().equalsIgnoreCase("authorization")) {
                return entry.getValue();
            }
        }
        return null;
    }
}
