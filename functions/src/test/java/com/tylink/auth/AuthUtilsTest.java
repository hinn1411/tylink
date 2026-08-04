package com.tylink.auth;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthUtilsTest {

    private static final String OWNER_ID = "11111111-1111-1111-1111-111111111111";

    private static APIGatewayV2HTTPEvent eventWithNoRequestContext() {
        return APIGatewayV2HTTPEvent.builder().build();
    }

    private static APIGatewayV2HTTPEvent eventWithNoAuthorizer() {
        return APIGatewayV2HTTPEvent.builder()
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder().build())
                .build();
    }

    private static APIGatewayV2HTTPEvent eventWithLambdaContext(Map<String, Object> lambdaContext) {
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer =
                APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
                        .withLambda(lambdaContext)
                        .build();
        return APIGatewayV2HTTPEvent.builder()
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withAuthorizer(authorizer)
                        .build())
                .build();
    }

    private static APIGatewayV2HTTPEvent eventWithJwtClaims(Map<String, String> claims) {
        APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT jwt =
                APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT.builder()
                        .withClaims(claims)
                        .build();
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer =
                APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
                        .withJwt(jwt)
                        .build();
        return APIGatewayV2HTTPEvent.builder()
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withAuthorizer(authorizer)
                        .build())
                .build();
    }

    @Test
    void extractOwnerId_lambdaContextHasOwnerId_returnsOwnerId() {
        APIGatewayV2HTTPEvent event = eventWithLambdaContext(Map.of("ownerId", OWNER_ID));

        String ownerId = AuthUtils.extractOwnerId(event);

        assertEquals(OWNER_ID, ownerId);
    }

    @Test
    void extractOwnerId_missingRequestContext_returnsNull() {
        APIGatewayV2HTTPEvent event = eventWithNoRequestContext();

        String ownerId = AuthUtils.extractOwnerId(event);

        assertNull(ownerId);
    }

    @Test
    void extractOwnerId_lambdaContextMissingOwnerId_returnsNull() {
        APIGatewayV2HTTPEvent event = eventWithLambdaContext(Map.of());

        String ownerId = AuthUtils.extractOwnerId(event);

        assertNull(ownerId);
    }

    @Test
    void extractOwnerIdFromJwtClaims_claimsHaveSub_returnsSub() {
        APIGatewayV2HTTPEvent event = eventWithJwtClaims(Map.of("sub", OWNER_ID));

        String ownerId = AuthUtils.extractOwnerIdFromJwtClaims(event);

        assertEquals(OWNER_ID, ownerId);
    }

    @Test
    void extractOwnerIdFromJwtClaims_missingRequestContext_returnsNull() {
        APIGatewayV2HTTPEvent event = eventWithNoRequestContext();

        String ownerId = AuthUtils.extractOwnerIdFromJwtClaims(event);

        assertNull(ownerId);
    }

    @Test
    void extractOwnerIdFromJwtClaims_missingAuthorizer_returnsNull() {
        APIGatewayV2HTTPEvent event = eventWithNoAuthorizer();

        String ownerId = AuthUtils.extractOwnerIdFromJwtClaims(event);

        assertNull(ownerId);
    }

    @Test
    void extractOwnerIdFromJwtClaims_authorizerMissingJwt_returnsNull() {
        APIGatewayV2HTTPEvent event = eventWithLambdaContext(Map.of("ownerId", OWNER_ID));

        String ownerId = AuthUtils.extractOwnerIdFromJwtClaims(event);

        assertNull(ownerId);
    }

    @Test
    void extractOwnerIdFromJwtClaims_claimsMissingSub_returnsNull() {
        APIGatewayV2HTTPEvent event = eventWithJwtClaims(Map.of("email", "user@example.com"));

        String ownerId = AuthUtils.extractOwnerIdFromJwtClaims(event);

        assertNull(ownerId);
    }
}
