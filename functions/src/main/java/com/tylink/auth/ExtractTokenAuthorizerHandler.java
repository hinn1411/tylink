package com.tylink.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2CustomAuthorizerEvent;
import com.tylink.utils.RequestUtils;
import com.tylink.utils.TylinkResultCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.metrics.FlushMetrics;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * This handler never rejects a request, it only extracts identify from JWT.
 * When the Authorization header carries a valid Cognito token, its verified {@code sub} claim
 * is forwarded unchanged as {@code sub} in the authorizer context; otherwise the context is
 * empty and downstream handlers treat the caller as anonymous.
 */
public class ExtractTokenAuthorizerHandler implements RequestHandler<APIGatewayV2CustomAuthorizerEvent, Map<String, Object>> {

    private static final Logger log = LogManager.getLogger(ExtractTokenAuthorizerHandler.class);

    private final CognitoJwtVerifier verifier;

    public ExtractTokenAuthorizerHandler() {
        this(new CognitoJwtVerifier(
                System.getenv("AWS_REGION"),
                System.getenv("USER_POOL_ID"),
                System.getenv("USER_POOL_CLIENT_ID")));
    }

    ExtractTokenAuthorizerHandler(CognitoJwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Logging
    @FlushMetrics(captureColdStart = true)
    @Override
    public Map<String, Object> handleRequest(APIGatewayV2CustomAuthorizerEvent input, Context context) {
        Optional<String> sub = verifier.verify(AuthUtils.extractRawJwtToken(input));

        log.info("owner is {}", sub.orElse("anonymous"));
        RequestUtils.recordSuccess(TylinkResultCode.SUCCESS);

        Map<String, Object> response = new HashMap<>();
        /* Must hardcore isAuthorized = true because we use EnableSimpleResponses.
        Otherwise, Gateway will reject it */
        response.put("isAuthorized", true);
        Map<String, String> authContext = new HashMap<>();
        authContext.put("sub", sub.orElse(""));
        response.put("context", authContext);
        return response;
    }
}
