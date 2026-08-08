package com.tylink.features.login;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.features.login.models.LoginRequest;
import com.tylink.utils.RequestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.utils.StringUtils;
import software.amazon.lambda.powertools.logging.Logging;

import java.util.HashMap;
import java.util.Map;

public class LoginHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LogManager.getLogger(LoginHandler.class);
    private static final Map<String, String> INVALID_CREDENTIALS_BODY = Map.of("message", "invalid username or password");

    private final CognitoIdentityProviderClient cognitoClient;
    private final String clientId;

    public LoginHandler() {
        this(CognitoIdentityProviderClient.create(), System.getenv("USER_POOL_CLIENT_ID"));
    }

    LoginHandler(CognitoIdentityProviderClient cognitoClient, String clientId) {
        this.cognitoClient = cognitoClient;
        this.clientId = clientId;
    }

    // Deliberately no logEvent = true will leak raw password
    // and Powertools event-logging would put it in CloudWatch.
    @Logging
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        LoginRequest request = RequestUtils.parseBody(input.getBody(), LoginRequest.class);
        if (request == null) {
            log.error("Request body is empty!");
            return RequestUtils.jsonResponse(400, Map.of("message", "invalid request body"));
        }
        if (StringUtils.isBlank(request.getUsername())) {
            log.error("Username is empty!");
            return RequestUtils.jsonResponse(400, Map.of("message", "username is required"));
        }
        if (StringUtils.isBlank(request.getPassword())) {
            log.error("Password is empty!");
            return RequestUtils.jsonResponse(400, Map.of("message", "password is required"));
        }

        return login(request.getUsername(), request.getPassword());
    }

    private APIGatewayV2HTTPResponse login(String username, String password) {
        InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .clientId(clientId)
                .authParameters(Map.of("USERNAME", username, "PASSWORD", password))
                .build();

        InitiateAuthResponse authResponse;
        try {
            authResponse = cognitoClient.initiateAuth(authRequest);
        } catch (NotAuthorizedException | UserNotFoundException e) {
            log.warn("Rejected login attempt: invalid credentials");
            return RequestUtils.jsonResponse(401, INVALID_CREDENTIALS_BODY);
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito InitiateAuth failed", e);
            return RequestUtils.jsonResponse(500, Map.of("message", "failed to authenticate"));
        }

        log.info("Login succeeded");
        return RequestUtils.jsonResponse(200, toResponseBody(authResponse.authenticationResult()));
    }

    private static Map<String, String> toResponseBody(AuthenticationResultType result) {
        Map<String, String> body = new HashMap<>();
        body.put("accessToken", result.accessToken());
        body.put("idToken", result.idToken());
        body.put("refreshToken", result.refreshToken());
        body.put("expiresIn", String.valueOf(result.expiresIn()));
        return body;
    }
}
