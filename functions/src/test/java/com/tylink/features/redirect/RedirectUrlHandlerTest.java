package com.tylink.features.redirect;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.models.ShortUrl;
import com.tylink.models.UrlStatus;
import com.tylink.models.Visibility;
import com.tylink.repository.UrlRepository;
import com.tylink.repository.UrlRepositoryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedirectUrlHandlerTest {

    private static final String SHORT_CODE = "aB3xY9Z";
    private static final String LONG_URL = "https://example.com/some/very/long/path";
    private static final String OWNER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_OWNER_ID = "22222222-2222-2222-2222-222222222222";

    private UrlRepository urlRepository;
    private RedirectUrlHandler handler;

    @BeforeEach
    void setUp() {
        urlRepository = mock(UrlRepository.class);
        handler = new RedirectUrlHandler(urlRepository);
    }

    private APIGatewayV2HTTPEvent eventFor(String shortCode, String ownerId) {
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer =
                APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
                        .withLambda(Map.of("ownerId", ownerId))
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

    private APIGatewayV2HTTPEvent anonymousEventFor(String shortCode) {
        return APIGatewayV2HTTPEvent.builder()
                .withPathParameters(Map.of("shortCode", shortCode))
                .build();
    }

    private ShortUrl shortUrl(String ownerId, Visibility visibility, UrlStatus status) {
        return new ShortUrl(SHORT_CODE, LONG_URL, ownerId, visibility, status, "2026-01-01T00:00:00Z");
    }

    @Test
    void redirectsPublicUrlForAnonymousCaller() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(null, Visibility.PUBLIC, UrlStatus.ACTIVE));

        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        assertEquals(307, response.getStatusCode());
        assertEquals(LONG_URL, response.getHeaders().get("Location"));
    }

    @Test
    void redirectsPublicUrlRegardlessOfCaller() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PUBLIC, UrlStatus.ACTIVE));

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(SHORT_CODE, OTHER_OWNER_ID), null);

        assertEquals(307, response.getStatusCode());
        assertEquals(LONG_URL, response.getHeaders().get("Location"));
    }

    @Test
    void notFoundForAnonymousCallerOnPrivateUrl() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PRIVATE, UrlStatus.ACTIVE));

        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void notFoundForWrongOwnerOnPrivateUrl() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PRIVATE, UrlStatus.ACTIVE));

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(SHORT_CODE, OTHER_OWNER_ID), null);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void redirectsPrivateUrlForOwningCaller() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PRIVATE, UrlStatus.ACTIVE));

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID), null);

        assertEquals(307, response.getStatusCode());
        assertEquals(LONG_URL, response.getHeaders().get("Location"));
    }

    @Test
    void notFoundWhenItemDoesNotExist() {
        when(urlRepository.findByShortCode(SHORT_CODE)).thenReturn(null);

        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void notFoundForShortCodeTooShort() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor("abc123"), null);

        assertEquals(404, response.getStatusCode());
        verify(urlRepository, never()).findByShortCode(anyString());
    }

    @Test
    void notFoundForShortCodeTooLong() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor("abc123456"), null);

        assertEquals(404, response.getStatusCode());
        verify(urlRepository, never()).findByShortCode(anyString());
    }

    @Test
    void notFoundForShortCodeWithInvalidCharacters() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor("abc-123"), null);

        assertEquals(404, response.getStatusCode());
        verify(urlRepository, never()).findByShortCode(anyString());
    }

    @Test
    void notFoundWhenPathParametersMissing() {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder().build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(404, response.getStatusCode());
        verify(urlRepository, never()).findByShortCode(anyString());
    }

    @Test
    void goneWhenPublicUrlIsDeleted() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(null, Visibility.PUBLIC, UrlStatus.DELETED));

        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        assertEquals(410, response.getStatusCode());
    }

    @Test
    void goneWhenOwnerRedirectsToDeletedPrivateUrl() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PRIVATE, UrlStatus.DELETED));

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID), null);

        assertEquals(410, response.getStatusCode());
    }

    @Test
    void notFoundWhenNonOwnerRequestsDeletedPrivateUrl() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PRIVATE, UrlStatus.DELETED));

        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void internalErrorWhenRepositoryFails() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenThrow(new UrlRepositoryException("service unavailable", new RuntimeException("boom")));

        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        assertEquals(500, response.getStatusCode());
    }

    @Test
    void allNotFoundResponsesHaveIdenticalBody() {
        when(urlRepository.findByShortCode(SHORT_CODE)).thenReturn(null);
        String notFoundBody = handler.handleRequest(anonymousEventFor(SHORT_CODE), null).getBody();

        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PRIVATE, UrlStatus.ACTIVE));
        String privateMismatchBody = handler.handleRequest(anonymousEventFor(SHORT_CODE), null).getBody();

        String malformedBody = handler.handleRequest(anonymousEventFor("bad"), null).getBody();

        assertEquals(notFoundBody, privateMismatchBody);
        assertEquals(notFoundBody, malformedBody);
    }
}
