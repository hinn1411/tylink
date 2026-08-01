package com.tylink.auth;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import software.amazon.awssdk.utils.StringUtils;

import java.util.Optional;

public final class AuthUtils {

    private AuthUtils() {
    }

    /**
     * ownerId is the userId ExtractTokenAuthorizerHandler puts in the authorizer context —
     * present only when the caller sent a valid Cognito token, empty for anonymous callers.
     */
    public static Optional<String> extractOwnerId(APIGatewayV2HTTPEvent input) {
        return Optional.ofNullable(input.getRequestContext())
                .map(APIGatewayV2HTTPEvent.RequestContext::getAuthorizer)
                .map(APIGatewayV2HTTPEvent.RequestContext.Authorizer::getLambda)
                .map(context -> context.get("ownerId"))
                .map(Object::toString)
                .filter(StringUtils::isNotBlank);
    }
}
