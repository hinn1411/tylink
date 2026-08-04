package com.tylink.features.list;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.model.ShortUrl;
import com.tylink.model.UrlStatus;
import com.tylink.model.Visibility;
import com.tylink.repository.InvalidCursorException;
import com.tylink.repository.UrlPage;
import com.tylink.repository.UrlRepository;
import com.tylink.repository.UrlRepositoryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListUrlsHandlerTest {

    private static final String OWNER_ID = "11111111-1111-1111-1111-111111111111";

    private UrlRepository urlRepository;
    private ListUrlsHandler handler;

    @BeforeEach
    void setUp() {
        urlRepository = mock(UrlRepository.class);
        handler = new ListUrlsHandler(urlRepository);
    }

    private APIGatewayV2HTTPEvent eventFor(String ownerId, Map<String, String> queryStringParameters) {
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
                .withRequestContext(requestContext)
                .withQueryStringParameters(queryStringParameters)
                .build();
    }

    private ShortUrl shortUrl(String shortCode) {
        return new ShortUrl(shortCode, "https://example.com/" + shortCode, OWNER_ID, Visibility.PRIVATE,
                UrlStatus.ACTIVE, "2026-01-01T00:00:00.000000000Z");
    }

    @Test
    void handleRequest_repositoryReturnsPage_returns200WithItemsAndNextCursor() {
        when(urlRepository.listByOwner(eq(OWNER_ID), anyInt(), any()))
                .thenReturn(new UrlPage(List.of(shortUrl("abc1234")), "next-cursor"));

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(OWNER_ID, null), null);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("\"shortCode\":\"abc1234\""));
        assertTrue(response.getBody().contains("\"longUrl\":\"https://example.com/abc1234\""));
        assertTrue(response.getBody().contains("\"createdAt\":\"2026-01-01T00:00:00.000000000Z\""));
        assertTrue(response.getBody().contains("\"nextCursor\":\"next-cursor\""));
    }

    @Test
    void handleRequest_queryStringParametersMissing_returns200WithDefaultLimitAndNullCursor() {
        when(urlRepository.listByOwner(eq(OWNER_ID), anyInt(), any()))
                .thenReturn(new UrlPage(List.of(), null));

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(OWNER_ID, null), null);

        assertEquals(200, response.getStatusCode());
        verify(urlRepository).listByOwner(OWNER_ID, 20, null);
    }

    @Test
    void handleRequest_limitQueryParamProvided_passesLimitToRepository() {
        when(urlRepository.listByOwner(eq(OWNER_ID), anyInt(), any()))
                .thenReturn(new UrlPage(List.of(), null));

        handler.handleRequest(eventFor(OWNER_ID, Map.of("limit", "5")), null);

        verify(urlRepository).listByOwner(OWNER_ID, 5, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "101"})
    void handleRequest_limitInvalid_returns400AndSkipsRepository(String invalidLimit) {
        APIGatewayV2HTTPResponse response =
                handler.handleRequest(eventFor(OWNER_ID, Map.of("limit", invalidLimit)), null);

        assertEquals(400, response.getStatusCode());
        verify(urlRepository, never()).listByOwner(anyString(), anyInt(), any());
    }

    @Test
    void handleRequest_cursorQueryParamProvided_passesCursorToRepository() {
        when(urlRepository.listByOwner(eq(OWNER_ID), anyInt(), any()))
                .thenReturn(new UrlPage(List.of(), null));

        handler.handleRequest(eventFor(OWNER_ID, Map.of("cursor", "abc-cursor")), null);

        verify(urlRepository).listByOwner(OWNER_ID, 20, "abc-cursor");
    }

    @Test
    void handleRequest_repositoryThrowsInvalidCursorException_returns400() {
        when(urlRepository.listByOwner(eq(OWNER_ID), anyInt(), any()))
                .thenThrow(new InvalidCursorException("malformed"));

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(OWNER_ID, Map.of("cursor", "garbage")), null);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void handleRequest_repositoryThrowsUrlRepositoryException_returns500() {
        when(urlRepository.listByOwner(eq(OWNER_ID), anyInt(), any()))
                .thenThrow(new UrlRepositoryException("service unavailable", new RuntimeException("boom")));

        APIGatewayV2HTTPResponse response = handler.handleRequest(eventFor(OWNER_ID, null), null);

        assertEquals(500, response.getStatusCode());
    }
}
