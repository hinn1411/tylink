package com.tylink.features.update;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.models.ShortUrl;
import com.tylink.models.UrlStatus;
import com.tylink.models.Visibility;
import com.tylink.repository.UpdateOutcome;
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

class UpdateUrlHandlerTest {

    private static final String SHORT_CODE = "aB3xY9Z";
    private static final String OWNER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String NEW_LONG_URL = "https://example.com/updated/path";

    private UrlRepository urlRepository;
    private UpdateUrlHandler handler;

    @BeforeEach
    void setUp() {
        urlRepository = mock(UrlRepository.class);
        handler = new UpdateUrlHandler(urlRepository);
    }

    private static APIGatewayV2HTTPEvent eventFor(String shortCode, String ownerId, String body) {
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
                .withBody(body)
                .build();
    }

    private static ShortUrl shortUrl(String longUrl, UrlStatus status, String updatedAt, String deletedAt) {
        return new ShortUrl(SHORT_CODE, longUrl, OWNER_ID, Visibility.PUBLIC, status,
                "2026-01-01T00:00:00.000000000Z", updatedAt, deletedAt);
    }

    @Test
    void handleRequest_ownerUpdatesOwnActiveUrl_returns200WithUpdatedLongUrl() {
        ShortUrl updated = shortUrl(NEW_LONG_URL, UrlStatus.ACTIVE, "2026-01-02T00:00:00.000000000Z", null);
        when(urlRepository.updateLongUrl(SHORT_CODE, OWNER_ID, NEW_LONG_URL))
                .thenReturn(UpdateOutcome.updated(updated));

        APIGatewayV2HTTPResponse response =
                handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID, "{\"longUrl\": \"" + NEW_LONG_URL + "\"}"), null);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains(NEW_LONG_URL));
    }

    @Test
    void handleRequest_repositoryReportsNotFound_returns404() {
        when(urlRepository.updateLongUrl(SHORT_CODE, OWNER_ID, NEW_LONG_URL)).thenReturn(UpdateOutcome.notFound());

        APIGatewayV2HTTPResponse response =
                handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID, "{\"longUrl\": \"" + NEW_LONG_URL + "\"}"), null);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void handleRequest_repositoryReportsAlreadyDeleted_returns410WithLastKnownState() {
        ShortUrl deleted = shortUrl("https://example.com/original", UrlStatus.DELETED,
                "2026-01-01T00:00:00.000000000Z", "2026-01-03T00:00:00.000000000Z");
        when(urlRepository.updateLongUrl(SHORT_CODE, OWNER_ID, NEW_LONG_URL))
                .thenReturn(UpdateOutcome.alreadyDeleted(deleted));

        APIGatewayV2HTTPResponse response =
                handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID, "{\"longUrl\": \"" + NEW_LONG_URL + "\"}"), null);

        assertEquals(410, response.getStatusCode());
        assertTrue(response.getBody().contains("https://example.com/original"));
        assertTrue(response.getBody().contains("2026-01-03T00:00:00.000000000Z"));
    }

    @Test
    void handleRequest_malformedShortCode_returns404() {
        APIGatewayV2HTTPResponse response =
                handler.handleRequest(eventFor("bad", OWNER_ID, "{\"longUrl\": \"" + NEW_LONG_URL + "\"}"), null);

        assertEquals(404, response.getStatusCode());
        verify(urlRepository, never()).updateLongUrl(anyString(), anyString(), anyString());
    }

    @Test
    void handleRequest_missingLongUrl_returns400() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID, "{}"), null);

        assertEquals(400, response.getStatusCode());
        verify(urlRepository, never()).updateLongUrl(anyString(), anyString(), anyString());
    }

    @Test
    void handleRequest_missingLongUrl_bodyContainsResultCode641() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID, "{}"), null);

        assertTrue(response.getBody().contains("\"code\":641"));
    }

    @Test
    void handleRequest_invalidLongUrlScheme_returns400() {
        APIGatewayV2HTTPResponse response =
                handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID, "{\"longUrl\": \"javascript:alert(1)\"}"), null);

        assertEquals(400, response.getStatusCode());
        verify(urlRepository, never()).updateLongUrl(anyString(), anyString(), anyString());
    }

    @Test
    void handleRequest_repositoryThrows_returns500() {
        when(urlRepository.updateLongUrl(SHORT_CODE, OWNER_ID, NEW_LONG_URL))
                .thenThrow(new UrlRepositoryException("service unavailable", new RuntimeException("boom")));

        APIGatewayV2HTTPResponse response =
                handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID, "{\"longUrl\": \"" + NEW_LONG_URL + "\"}"), null);

        assertEquals(500, response.getStatusCode());
    }
}
