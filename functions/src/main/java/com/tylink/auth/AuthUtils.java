package com.tylink.auth;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2CustomAuthorizerEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import software.amazon.awssdk.utils.StringUtils;

import java.util.Map;
import java.util.Optional;

public final class AuthUtils {

    private AuthUtils() {
    }

    /**
     * Extract the caller's user id ({@code sub}) from whichever authorizer produced this request:
     * the custom Lambda authorizer's context map, or a native JWT authorizer's claims.
     */
    public static String extractOwnerId(APIGatewayV2HTTPEvent input) {
        return extractFromLambdaContext(input)
                .or(() -> extractFromJwtClaims(input))
                .orElse(null);
    }

    private static Optional<String> extractFromLambdaContext(APIGatewayV2HTTPEvent input) {
        return Optional.ofNullable(input.getRequestContext())
                .map(APIGatewayV2HTTPEvent.RequestContext::getAuthorizer)
                .map(APIGatewayV2HTTPEvent.RequestContext.Authorizer::getLambda)
                .map(context -> context.get("sub"))
                .map(Object::toString)
                .filter(StringUtils::isNotBlank);
    }

    private static Optional<String> extractFromJwtClaims(APIGatewayV2HTTPEvent input) {
        return Optional.ofNullable(input.getRequestContext())
                .map(APIGatewayV2HTTPEvent.RequestContext::getAuthorizer)
                .map(APIGatewayV2HTTPEvent.RequestContext.Authorizer::getJwt)
                .map(APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT::getClaims)
                .map(claims -> claims.get("sub"))
                .filter(StringUtils::isNotBlank);
    }

    /**
     * Extract the raw bearer token from the Authorization header of a custom authorizer event.
     */
    public static String extractRawJwtToken(APIGatewayV2CustomAuthorizerEvent input) {
        Map<String, String> headers = input.getHeaders();
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("authorization"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
