package com.tylink.features.shorten;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.model.ShortUrl;
import com.tylink.model.Visibility;
import com.tylink.repository.UrlRepository;
import com.tylink.repository.UrlRepositoryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ShortenUrlHandlerTest {

    private UrlRepository urlRepository;
    private ShortenUrlHandler handler;

    @BeforeEach
    void setUp() {
        urlRepository = mock(UrlRepository.class);
        handler = new ShortenUrlHandler(urlRepository);
    }

    /**
     * Mimics what ExtractTokenAuthorizerHandler actually returns as its authorizer context after
     * verifying a token — see com.tylink.auth.ExtractTokenAuthorizerHandler.
     */
    private APIGatewayV2HTTPEvent eventWithOwnerId(String body, String ownerId) {
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer =
                APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
                        .withLambda(Map.of("ownerId", ownerId))
                        .build();
        APIGatewayV2HTTPEvent.RequestContext requestContext =
                APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withAuthorizer(authorizer)
                        .build();
        return APIGatewayV2HTTPEvent.builder()
                .withBody(body)
                .withRequestContext(requestContext)
                .build();
    }

    @Test
    void createsPublicUrlAnonymouslyWhenNoAuthenticatedCaller() {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("{\"longUrl\": \"https://example.com/some/very/long/path\"}")
                .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(201, response.getStatusCode());
        assertTrue(response.getBody().contains("\"visibility\":\"PUBLIC\""));

        ArgumentCaptor<ShortUrl> captor = ArgumentCaptor.forClass(ShortUrl.class);
        verify(urlRepository).save(captor.capture());
        assertNull(captor.getValue().ownerId());
    }

    @Test
    void rejectsPrivateUrlWithNoAuthenticatedCaller() {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("{\"longUrl\": \"https://example.com/some/very/long/path\", \"visibility\": \"PRIVATE\"}")
                .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(401, response.getStatusCode());
    }

    @Test
    void createsPublicUrlAndTagsItWithOwnerFromAuthorizerContext() {
        APIGatewayV2HTTPEvent event = eventWithOwnerId(
                "{\"longUrl\": \"https://example.com/some/very/long/path\", \"visibility\": \"PUBLIC\"}",
                "11111111-1111-1111-1111-111111111111");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(201, response.getStatusCode());
        assertTrue(response.getBody().contains("\"visibility\":\"PUBLIC\""));

        ArgumentCaptor<ShortUrl> captor = ArgumentCaptor.forClass(ShortUrl.class);
        verify(urlRepository).save(captor.capture());
        assertEquals(Visibility.PUBLIC, captor.getValue().visibility());
        assertEquals("11111111-1111-1111-1111-111111111111", captor.getValue().ownerId());
    }

    @Test
    void createsPrivateUrlOwnedByCaller() {
        APIGatewayV2HTTPEvent event = eventWithOwnerId(
                "{\"longUrl\": \"https://example.com/bobs-private-dashboard\", \"visibility\": \"PRIVATE\"}",
                "22222222-2222-2222-2222-222222222222");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(201, response.getStatusCode());
        assertTrue(response.getBody().contains("\"visibility\":\"PRIVATE\""));

        ArgumentCaptor<ShortUrl> captor = ArgumentCaptor.forClass(ShortUrl.class);
        verify(urlRepository).save(captor.capture());
        assertEquals(Visibility.PRIVATE, captor.getValue().visibility());
        assertEquals("22222222-2222-2222-2222-222222222222", captor.getValue().ownerId());
    }

    @Test
    void rejectsMissingLongUrl() {
        APIGatewayV2HTTPEvent event = eventWithOwnerId("{}", "11111111-1111-1111-1111-111111111111");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void rejectsInvalidVisibility() {
        APIGatewayV2HTTPEvent event = eventWithOwnerId(
                "{\"longUrl\": \"https://example.com/x\", \"visibility\": \"SECRET\"}",
                "11111111-1111-1111-1111-111111111111");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void rejectsNonHttpProtocol() {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("{\"longUrl\": \"javascript:alert(1)\"}")
                .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void rejectsUrlWithHtmlPayload() {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("{\"longUrl\": \"http://example.com/<script>alert(1)</script>\"}")
                .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void rejectsUrlWithControlCharacters() {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("{\"longUrl\": \"http://example.com/\\r\\nSet-Cookie: evil=1\"}")
                .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void acceptsUrlWithQueryParametersAndFragment() {
        String longUrl = "https://example.com/path?foo=bar&baz=qux#section";
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("{\"longUrl\": \"" + longUrl + "\"}")
                .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(201, response.getStatusCode());

        ArgumentCaptor<ShortUrl> captor = ArgumentCaptor.forClass(ShortUrl.class);
        verify(urlRepository).save(captor.capture());
        assertEquals(longUrl, captor.getValue().longUrl());
    }

    @Test
    void returns500WhenUrlRepositorySaveFails() {
        doThrow(new UrlRepositoryException("service unavailable", new RuntimeException("boom")))
                .when(urlRepository).save(any(ShortUrl.class));

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("{\"longUrl\": \"https://example.com/some/very/long/path\"}")
                .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(500, response.getStatusCode());
    }
}
