package com.tylink.auth;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.tylink.utils.SnapStartWarmup;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.interfaces.RSAPublicKey;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Verifies a Cognito-issued JWT's signature against the User Pool's JWKS, without relying on
 * API Gateway to do it — this route can't carry a native JWT authorizer (it must stay open to
 * anonymous callers), so verification happens here instead, in a Lambda authorizer.
 */
public class CognitoJwtVerifier {

    private static final Logger log = LogManager.getLogger(CognitoJwtVerifier.class);

    private final String issuer;
    private final String clientId;
    private final JwkProvider jwkProvider;

    public CognitoJwtVerifier(String region, String userPoolId, String clientId) {
        this.issuer = "https://cognito-idp." + region + ".amazonaws.com/" + userPoolId;
        this.clientId = clientId;
        this.jwkProvider = new JwkProviderBuilder(issuer)
                .cached(10, 24, TimeUnit.HOURS)
                .rateLimited(10, 1, TimeUnit.MINUTES)
                .build();
        SnapStartWarmup.registerAfterRestore(this::warmUp);
    }

    private void warmUp() {
        try {
            jwkProvider.get("snapstart-warmup");
        } catch (JwkException e) {
            log.info("Expect warm up request fails!", e);
        }
    }

    /**
     * Returns the token's verified {@code sub} claim, or empty if the token is missing,
     * malformed, expired, signed by an untrusted key, or issued for a different app client.
     * Any of those is treated identically by callers: "no verified identity," not an error.
     */
    public Optional<String> verify(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return Optional.empty();
        }
        String token = bearerToken.startsWith("Bearer ") ? bearerToken.substring(7) : bearerToken;

        try {
            String keyId = JWT.decode(token).getKeyId();
            Jwk jwk = jwkProvider.get(keyId);
            Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null);

            DecodedJWT verified = JWT.require(algorithm)
                    .withIssuer(issuer)
                    .build()
                    .verify(token);

            if (!isTrustedAudience(verified)) {
                log.warn("Rejected token: issued for an untrusted app client");
                return Optional.empty();
            }
            return Optional.ofNullable(verified.getSubject());
        } catch (JWTVerificationException | JwkException e) {
            log.warn("Rejected token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * ID tokens carry the client ID in {@code aud}; access tokens carry it in {@code client_id}.
     * Checking this prevents a token issued for a different app client on the same User Pool
     * from being accepted here (token confusion).
     */
    private boolean isTrustedAudience(DecodedJWT jwt) {
        String tokenUse = jwt.getClaim("token_use").asString();
        if ("id".equals(tokenUse)) {
            return jwt.getAudience() != null && jwt.getAudience().contains(clientId);
        }
        if ("access".equals(tokenUse)) {
            return clientId.equals(jwt.getClaim("client_id").asString());
        }
        return false;
    }
}
