package com.tylink.features.login;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.features.login.models.LoginRequest;
import com.tylink.utils.RequestUtils;
import com.tylink.utils.SnapStartWarmup;
import com.tylink.utils.TracingUtils;
import com.tylink.utils.TylinkResultCode;
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
import software.amazon.lambda.powertools.metrics.FlushMetrics;

import java.util.HashMap;
import java.util.Map;

public class LoginHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LogManager.getLogger(LoginHandler.class);

    private final CognitoIdentityProviderClient cognitoClient;
    private final String clientId;

    public LoginHandler() {
        this(CognitoIdentityProviderClient.builder()
                        .overrideConfiguration(TracingUtils.xrayOverrideConfiguration())
                        .build(),
                System.getenv("USER_POOL_CLIENT_ID"));
        SnapStartWarmup.registerAfterRestore(this::warmUp);
    }

    LoginHandler(CognitoIdentityProviderClient cognitoClient, String clientId) {
        this.cognitoClient = cognitoClient;
        this.clientId = clientId;
    }

    private void warmUp() {
        try {
            cognitoClient.initiateAuth(InitiateAuthRequest.builder()
                    .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                    .clientId(clientId)
                    .authParameters(Map.of("USERNAME", "snapstart-warmup", "PASSWORD", "invalid"))
                    .build());
        } catch (CognitoIdentityProviderException e) {
            log.info("Expect warn up request fails!");
        }
    }

    // logEvent = true will leak raw password
    @Logging
    @FlushMetrics(captureColdStart = true)
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        LoginRequest request = RequestUtils.parseBody(input.getBody(), LoginRequest.class);
        if (request == null) {
            log.error("Request body is empty!");
            return RequestUtils.errorResponse(TylinkResultCode.LOGIN_INVALID_REQUEST_BODY);
        }
        if (StringUtils.isBlank(request.getUsername())) {
            log.error("Username is empty!");
            return RequestUtils.errorResponse(TylinkResultCode.LOGIN_USERNAME_REQUIRED);
        }
        if (StringUtils.isBlank(request.getPassword())) {
            log.error("Password is empty!");
            return RequestUtils.errorResponse(TylinkResultCode.LOGIN_PASSWORD_REQUIRED);
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
            log.warn("Login attempt rejected. Cause by: {}", e.getClass().getSimpleName());
            return RequestUtils.errorResponse(TylinkResultCode.LOGIN_INVALID_CREDENTIALS);
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito InitiateAuth failed", e);
            return RequestUtils.errorResponse(TylinkResultCode.LOGIN_FAILED);
        }

        log.info("Login succeeded");
        return RequestUtils.successResponse(200, toResponseBody(authResponse.authenticationResult()));
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
