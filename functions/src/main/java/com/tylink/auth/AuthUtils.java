package com.tylink.auth;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import software.amazon.awssdk.utils.StringUtils;

import java.util.Optional;

public final class AuthUtils {

    private AuthUtils() {
    }

    /**
     * Extract userId from custom Authorizer
     */
    public static String extractOwnerId(APIGatewayV2HTTPEvent input) {
        return Optional.ofNullable(input.getRequestContext())
                .map(APIGatewayV2HTTPEvent.RequestContext::getAuthorizer)
                .map(APIGatewayV2HTTPEvent.RequestContext.Authorizer::getLambda)
                .map(context -> context.get("ownerId"))
                .map(Object::toString)
                .filter(StringUtils::isNotBlank)
                .orElse(null);
    }

    /**
     * Extract sub (userId) from Native JWT Authorizer
     */
    public static String extractOwnerIdFromJwtClaims(APIGatewayV2HTTPEvent input) {
        return Optional.ofNullable(input.getRequestContext())
                .map(APIGatewayV2HTTPEvent.RequestContext::getAuthorizer)
                .map(APIGatewayV2HTTPEvent.RequestContext.Authorizer::getJwt)
                .map(APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT::getClaims)
                .map(claims -> claims.get("sub"))
                .filter(StringUtils::isNotBlank)
                .orElse(null);
    }
}
