package com.tylink.features.delete;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.repository.UrlRepository;
import com.tylink.repository.UrlRepositoryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeleteUrlHandlerTest {

    private static final String SHORT_CODE = "aB3xY9Z";
    private static final String OWNER_ID = "11111111-1111-1111-1111-111111111111";

    private UrlRepository urlRepository;
    private DeleteUrlHandler handler;

    @BeforeEach
    void setUp() {
        urlRepository = mock(UrlRepository.class);
        handler = new DeleteUrlHandler(urlRepository);
    }

    private static APIGatewayV2HTTPEvent eventFor(String shortCode, String ownerId) {
        APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT jwt =
                APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT.builder()
                        .withClaims(Map.of("sub", ownerId))
                        .build();
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer =
                APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
                        .withJwt(jwt)
                        .build();
        APIGatewayV2HTTPEvent.RequestContext requestContext =
                APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withAuthorizer(authorizer)
                        .build();
        return APIGatewayV2HTTPEvent.builder()
                .withPathParameters(Map.of("shortCode", shortCode))
                .withRequestContext(requestContext)
                .build();
    }

    @Test
    void handleRequest_ownerDeletesOwnUrl_returns410() {
        when(urlRepository.markDeleted(SHORT_CODE, OWNER_ID)).thenReturn(true);

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID), null);

        assertEquals(410, response.getStatusCode());
        verify(urlRepository).markDeleted(SHORT_CODE, OWNER_ID);
    }

    @Test
    void handleRequest_repositoryReportsConditionFailed_returns404() {
        when(urlRepository.markDeleted(SHORT_CODE, OWNER_ID)).thenReturn(false);

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID), null);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void handleRequest_malformedShortCode_returns404() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor("bad", OWNER_ID), null);

        assertEquals(404, response.getStatusCode());
        verify(urlRepository, never()).markDeleted(anyString(), anyString());
    }

    @Test
    void handleRequest_malformedShortCode_bodyContainsResultCode630() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor("bad", OWNER_ID), null);

        assertTrue(response.getBody().contains("\"code\":630"));
    }

    @Test
    void handleRequest_repositoryThrows_returns500() {
        when(urlRepository.markDeleted(SHORT_CODE, OWNER_ID))
                .thenThrow(new UrlRepositoryException("service unavailable", new RuntimeException("boom")));

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID), null);

        assertEquals(500, response.getStatusCode());
    }
}
