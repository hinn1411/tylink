package com.tylink.features.shorten;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.auth.AuthUtils;
import com.tylink.features.shorten.models.IdempotentCreateRequest;
import com.tylink.features.shorten.models.ShortenUrlRequest;
import com.tylink.models.ShortUrl;
import com.tylink.models.Visibility;
import com.tylink.repository.dynamodb.DynamoDbUrlRepository;
import com.tylink.repository.UrlRepository;
import com.tylink.repository.UrlRepositoryException;
import com.tylink.utils.LongUrlValidator;
import com.tylink.utils.RequestUtils;
import com.tylink.utils.ShortCodeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.utils.StringUtils;
import software.amazon.lambda.powertools.idempotency.Idempotency;
import software.amazon.lambda.powertools.idempotency.IdempotencyConfig;
import software.amazon.lambda.powertools.idempotency.Idempotent;
import software.amazon.lambda.powertools.idempotency.exceptions.IdempotencyAlreadyInProgressException;
import software.amazon.lambda.powertools.idempotency.exceptions.IdempotencyValidationException;
import software.amazon.lambda.powertools.idempotency.persistence.dynamodb.DynamoDBPersistenceStore;
import software.amazon.lambda.powertools.logging.Logging;

import java.time.Duration;
import java.util.Map;

public class ShortenUrlHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LogManager.getLogger(ShortenUrlHandler.class);

    private final UrlRepository urlRepository;

    public ShortenUrlHandler() {
        this(new DynamoDbUrlRepository(DynamoDbClient.create(), System.getenv("TABLE_NAME")));
        Idempotency.config()
                .withPersistenceStore(DynamoDBPersistenceStore.builder()
                        .withTableName(System.getenv("IDEMPOTENCY_TABLE_NAME"))
                        .build())
                .withConfig(IdempotencyConfig.builder()
                        .withEventKeyJMESPath("idempotencyKey")
                        .withPayloadValidationJMESPath("longUrl")
                        .withExpiration(Duration.ofMinutes(5))
                        .build())
                .configure();
    }

    ShortenUrlHandler(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }

    @Logging(logEvent = true)
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        log.info("Event: {}", input);
        ShortenUrlRequest request = RequestUtils.parseBody(input.getBody(), ShortenUrlRequest.class);
        if (request == null) {
            log.error("Request body is empty!");
            return RequestUtils.jsonResponse(400, Map.of("message", "invalid request body"));
        }
        if (StringUtils.isBlank(request.getLongUrl())) {
            log.error("Original URL is empty!");
            return RequestUtils.jsonResponse(400, Map.of("message", "longUrl is required"));
        }
        String idempotencyKey = RequestUtils.getHeader(input, "Idempotency-Key");
        if (StringUtils.isBlank(idempotencyKey)) {
            log.warn("Rejected create URL request: missing Idempotency-Key header");
            return RequestUtils.jsonResponse(400, Map.of("message", "Idempotency-Key header is required"));
        }
        String longUrl = LongUrlValidator.validate(request.getLongUrl());
        if (longUrl == null) {
            log.error("Raw URL: {} is invalid!", request.getLongUrl());
            return RequestUtils.jsonResponse(400, Map.of("message", "longUrl must be a valid http or https URL"));
        }

        Visibility visibility = Visibility.parse(request.getVisibility());
        if (visibility == null) {
            log.error("Visibility: {} is invalid!");
            return RequestUtils.jsonResponse(400, Map.of("message", "visibility must be PUBLIC or PRIVATE"));
        }

        String ownerId = AuthUtils.extractOwnerId(input);
        log.info("ownerId: {}", ownerId);
        // Public access cannot create private urls
        if (visibility == Visibility.PRIVATE && ownerId == null) {
            log.warn("Rejected create URL request: PRIVATE visibility requires an authenticated caller");
            return RequestUtils.jsonResponse(401, Map.of("message", "unauthorized"));
        }

        return createUrl(idempotencyKey, longUrl, ownerId, visibility);
    }

    private APIGatewayV2HTTPResponse createUrl(String idempotencyKey, String longUrl, String ownerId, Visibility visibility) {
        try {
            IdempotentCreateRequest request = new IdempotentCreateRequest(idempotencyKey, longUrl, ownerId, visibility);
            return createUrlIdempotent(request);
        } catch (IdempotencyValidationException e) {
            log.warn("Rejected create URL request: Idempotency-Key reused with a different longUrl");
            return RequestUtils.jsonResponse(409, Map.of("message", "Idempotency-Key already used with a different request"));
        } catch (IdempotencyAlreadyInProgressException e) {
            log.warn("Rejected create URL request: duplicate in-flight request for this Idempotency-Key");
            return RequestUtils.jsonResponse(409, Map.of("message", "a request with this Idempotency-Key is already being processed"));
        } catch (UrlRepositoryException e) {
            log.error("Failed to write short url", e);
            return RequestUtils.jsonResponse(500, Map.of("message", "failed to create short url"));
        }
    }

    @Idempotent
    APIGatewayV2HTTPResponse createUrlIdempotent(IdempotentCreateRequest request) {
        ShortUrl shortUrl = ShortUrl.create(ShortCodeUtils.generate(), request.longUrl(), request.ownerId(), request.visibility());
        urlRepository.save(shortUrl);

        log.info("Created shortCode={} visibility={} anonymous={}", shortUrl.shortCode(), request.visibility(), request.ownerId() == null);
        return RequestUtils.jsonResponse(201, Map.of("shortCode", shortUrl.shortCode(), "visibility", request.visibility().name()));
    }
}
