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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private static final long MAX_AGE_SECONDS = 300L;

    private UrlRepository urlRepository;
    private RedirectUrlHandler handler;

    @BeforeEach
    void setUp() {
        urlRepository = mock(UrlRepository.class);
        handler = new RedirectUrlHandler(urlRepository, MAX_AGE_SECONDS);
    }

    private APIGatewayV2HTTPEvent eventFor(String shortCode, String ownerId) {
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer =
                APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
                        .withLambda(Map.of("sub", ownerId))
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
        return new ShortUrl(SHORT_CODE, LONG_URL, ownerId, visibility, status, "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z", null);
    }

    @Test
    void handleRequest_anonymousCallerRequestsPublicUrl_redirects() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(null, Visibility.PUBLIC, UrlStatus.ACTIVE));

        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        assertEquals(307, response.getStatusCode());
        assertEquals(LONG_URL, response.getHeaders().get("Location"));
    }

    @Test
    void handleRequest_publicActiveUrl_setsCacheControlPublicMaxAge() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(null, Visibility.PUBLIC, UrlStatus.ACTIVE));

        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        assertEquals("public, max-age=" + MAX_AGE_SECONDS, response.getHeaders().get("Cache-Control"));
    }

    @Test
    void handleRequest_nonOwnerRequestsPublicUrl_redirects() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PUBLIC, UrlStatus.ACTIVE));

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(SHORT_CODE, OTHER_OWNER_ID), null);

        assertEquals(307, response.getStatusCode());
        assertEquals(LONG_URL, response.getHeaders().get("Location"));
    }

    @Test
    void handleRequest_anonymousCallerRequestsPrivateUrl_returns404() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PRIVATE, UrlStatus.ACTIVE));

        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void handleRequest_nonOwnerRequestsPrivateUrl_returns404() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PRIVATE, UrlStatus.ACTIVE));

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(SHORT_CODE, OTHER_OWNER_ID), null);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void handleRequest_ownerRequestsPrivateUrl_redirects() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PRIVATE, UrlStatus.ACTIVE));

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID), null);

        assertEquals(307, response.getStatusCode());
        assertEquals(LONG_URL, response.getHeaders().get("Location"));
    }

    @Test
    void handleRequest_privateActiveUrlOwnerCaller_omitsCacheControlHeader() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PRIVATE, UrlStatus.ACTIVE));

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID), null);

        assertNull(response.getHeaders().get("Cache-Control"));
    }

    @Test
    void handleRequest_shortCodeNotFound_returns404() {
        when(urlRepository.findByShortCode(SHORT_CODE)).thenReturn(null);

        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void handleRequest_shortCodeNotFound_bodyContainsResultCode610() {
        when(urlRepository.findByShortCode(SHORT_CODE)).thenReturn(null);

        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        assertTrue(response.getBody().contains("\"code\":610"));
    }

    @Test
    void handleRequest_shortCodeTooShort_returns404() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor("abc123"), null);

        assertEquals(404, response.getStatusCode());
        verify(urlRepository, never()).findByShortCode(anyString());
    }

    @Test
    void handleRequest_shortCodeTooLong_returns404() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor("abc123456"), null);

        assertEquals(404, response.getStatusCode());
        verify(urlRepository, never()).findByShortCode(anyString());
    }

    @Test
    void handleRequest_shortCodeWithInvalidCharacters_returns404() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor("abc-123"), null);

        assertEquals(404, response.getStatusCode());
        verify(urlRepository, never()).findByShortCode(anyString());
    }

    @Test
    void handleRequest_pathParametersMissing_returns404() {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder().build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(404, response.getStatusCode());
        verify(urlRepository, never()).findByShortCode(anyString());
    }

    @Test
    void handleRequest_publicUrlDeleted_returns410() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(null, Visibility.PUBLIC, UrlStatus.DELETED));

        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        assertEquals(410, response.getStatusCode());
    }

    @Test
    void handleRequest_ownerRequestsDeletedPrivateUrl_returns410() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PRIVATE, UrlStatus.DELETED));

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(SHORT_CODE, OWNER_ID), null);

        assertEquals(410, response.getStatusCode());
    }

    @Test
    void handleRequest_nonOwnerRequestsDeletedPrivateUrl_returns404() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PRIVATE, UrlStatus.DELETED));

        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void handleRequest_repositoryThrows_returns500() {
        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenThrow(new UrlRepositoryException("service unavailable", new RuntimeException("boom")));

        APIGatewayV2HTTPResponse response = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        assertEquals(500, response.getStatusCode());
    }

    @Test
    void handleRequest_variousNotFoundCauses_returnsIdenticalBody() {
        when(urlRepository.findByShortCode(SHORT_CODE)).thenReturn(null);
        APIGatewayV2HTTPResponse missingResponse = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        when(urlRepository.findByShortCode(SHORT_CODE))
                .thenReturn(shortUrl(OWNER_ID, Visibility.PRIVATE, UrlStatus.ACTIVE));
        APIGatewayV2HTTPResponse privateMismatchResponse = handler.handleRequest(anonymousEventFor(SHORT_CODE), null);

        APIGatewayV2HTTPResponse malformedResponse = handler.handleRequest(anonymousEventFor("bad"), null);

        assertEquals(missingResponse.getBody(), privateMismatchResponse.getBody());
        assertEquals(missingResponse.getBody(), malformedResponse.getBody());
    }
}
