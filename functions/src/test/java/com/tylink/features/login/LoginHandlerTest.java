package com.tylink.features.login;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginHandlerTest {

    private static final String CLIENT_ID = "test-client-id";

    private CognitoIdentityProviderClient cognitoClient;
    private LoginHandler handler;

    @BeforeEach
    void setUp() {
        cognitoClient = mock(CognitoIdentityProviderClient.class);
        handler = new LoginHandler(cognitoClient, CLIENT_ID);
    }

    private static APIGatewayV2HTTPEvent eventWithBody(String body) {
        return APIGatewayV2HTTPEvent.builder().withBody(body).build();
    }

    private static InitiateAuthResponse successfulAuthResponse() {
        AuthenticationResultType result = AuthenticationResultType.builder()
                .accessToken("access-token")
                .idToken("id-token")
                .refreshToken("refresh-token")
                .expiresIn(3600)
                .build();
        return InitiateAuthResponse.builder().authenticationResult(result).build();
    }

    @Test
    void handleRequest_nullBody_returns400() {
        APIGatewayV2HTTPEvent event = eventWithBody(null);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void handleRequest_missingUsername_returns400() {
        APIGatewayV2HTTPEvent event = eventWithBody("{\"password\": \"secret123\"}");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void handleRequest_missingPassword_returns400() {
        APIGatewayV2HTTPEvent event = eventWithBody("{\"username\": \"user@example.com\"}");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void handleRequest_cognitoRejectsCredentials_returns401WithGenericMessage() {
        when(cognitoClient.initiateAuth(any(InitiateAuthRequest.class)))
                .thenThrow(NotAuthorizedException.builder().message("Incorrect username or password.").build());
        APIGatewayV2HTTPEvent event =
                eventWithBody("{\"username\": \"user@example.com\", \"password\": \"wrong-password\"}");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(401, response.getStatusCode());
        assertTrue(response.getBody().contains("invalid username or password"));
    }

    @Test
    void handleRequest_userDoesNotExist_returns401WithSameGenericMessageAsWrongPassword() {
        when(cognitoClient.initiateAuth(any(InitiateAuthRequest.class)))
                .thenThrow(UserNotFoundException.builder().message("User does not exist.").build());
        APIGatewayV2HTTPEvent event =
                eventWithBody("{\"username\": \"nobody@example.com\", \"password\": \"whatever123\"}");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(401, response.getStatusCode());
        assertTrue(response.getBody().contains("invalid username or password"));
    }

    @Test
    void handleRequest_validCredentials_returns200WithTokens() {
        when(cognitoClient.initiateAuth(any(InitiateAuthRequest.class))).thenReturn(successfulAuthResponse());
        APIGatewayV2HTTPEvent event =
                eventWithBody("{\"username\": \"user@example.com\", \"password\": \"correct-password\"}");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("\"accessToken\":\"access-token\""));
        assertTrue(response.getBody().contains("\"idToken\":\"id-token\""));
        assertTrue(response.getBody().contains("\"refreshToken\":\"refresh-token\""));
        assertTrue(response.getBody().contains("\"expiresIn\":\"3600\""));
    }

    @Test
    void handleRequest_validCredentials_callsCognitoWithUserPasswordAuthFlow() {
        when(cognitoClient.initiateAuth(any(InitiateAuthRequest.class))).thenReturn(successfulAuthResponse());
        APIGatewayV2HTTPEvent event =
                eventWithBody("{\"username\": \"user@example.com\", \"password\": \"correct-password\"}");

        handler.handleRequest(event, null);

        ArgumentCaptor<InitiateAuthRequest> captor = ArgumentCaptor.forClass(InitiateAuthRequest.class);
        verify(cognitoClient).initiateAuth(captor.capture());
        InitiateAuthRequest request = captor.getValue();
        assertEquals(AuthFlowType.USER_PASSWORD_AUTH, request.authFlow());
        assertEquals(CLIENT_ID, request.clientId());
        assertEquals("user@example.com", request.authParameters().get("USERNAME"));
        assertEquals("correct-password", request.authParameters().get("PASSWORD"));
    }
}
