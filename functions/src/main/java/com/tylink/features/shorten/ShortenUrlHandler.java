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
import com.tylink.utils.SnapStartWarmup;
import com.tylink.utils.TracingUtils;
import com.tylink.utils.TylinkResultCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.utils.StringUtils;
import software.amazon.lambda.powertools.idempotency.Idempotency;
import software.amazon.lambda.powertools.idempotency.IdempotencyConfig;
import software.amazon.lambda.powertools.idempotency.Idempotent;
import software.amazon.lambda.powertools.idempotency.exceptions.IdempotencyAlreadyInProgressException;
import software.amazon.lambda.powertools.idempotency.exceptions.IdempotencyValidationException;
import software.amazon.lambda.powertools.idempotency.persistence.dynamodb.DynamoDBPersistenceStore;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.metrics.FlushMetrics;
import software.amazon.lambda.powertools.metrics.Metrics;
import software.amazon.lambda.powertools.metrics.MetricsFactory;
import software.amazon.lambda.powertools.metrics.model.MetricUnit;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public class ShortenUrlHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LogManager.getLogger(ShortenUrlHandler.class);
    private static final Metrics metrics = MetricsFactory.getMetricsInstance();
    static final int MAX_SHORT_CODE_ATTEMPTS = 3;

    private final UrlRepository urlRepository;
    private DynamoDbClient idempotencyClient;
    private String idempotencyTableName;

    public ShortenUrlHandler() {
        this(new DynamoDbUrlRepository(createDynamoDbClient(), System.getenv("TABLE_NAME")));
        idempotencyTableName = System.getenv("IDEMPOTENCY_TABLE_NAME");
        idempotencyClient = createDynamoDbClient();
        Idempotency.config()
                .withPersistenceStore(DynamoDBPersistenceStore.builder()
                        .withTableName(idempotencyTableName)
                        .withDynamoDbClient(idempotencyClient)
                        .build())
                .withConfig(IdempotencyConfig.builder()
                        .withEventKeyJMESPath("idempotencyKey")
                        .withPayloadValidationJMESPath("longUrl")
                        .withExpiration(Duration.ofMinutes(5))
                        .build())
                .configure();
        SnapStartWarmup.registerAfterRestore(this::warmUp);
    }

    ShortenUrlHandler(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }

    private static DynamoDbClient createDynamoDbClient() {
        return DynamoDbClient.builder()
                .overrideConfiguration(TracingUtils.xrayOverrideConfiguration())
                .build();
    }

    // Separate DynamoDbClient from DynamoDbUrlRepository's — this one backs Powertools'
    // Idempotency persistence store and needs its own SnapStart re-prime.
    private void warmUp() {
        try {
            idempotencyClient.getItem(GetItemRequest.builder()
                    .tableName(idempotencyTableName)
                    .key(Map.of("id", AttributeValue.fromS("SNAPSTART_WARMUP")))
                    .build());
        } catch (SdkException e) {
            log.warn("SnapStart warm-up GetItem failed, continuing anyway", e);
        }
    }

    @Logging(logEvent = true)
    @FlushMetrics(captureColdStart = true)
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        log.info("Event: {}", input);
        ShortenUrlRequest request = RequestUtils.parseBody(input.getBody(), ShortenUrlRequest.class);
        if (request == null) {
            log.error("Request body is empty!");
            return RequestUtils.errorResponse(TylinkResultCode.SHORTEN_INVALID_REQUEST_BODY);
        }
        if (StringUtils.isBlank(request.getLongUrl())) {
            log.error("Original URL is empty!");
            return RequestUtils.errorResponse(TylinkResultCode.SHORTEN_LONG_URL_REQUIRED);
        }
        String idempotencyKey = RequestUtils.getHeader(input, "Idempotency-Key");
        if (StringUtils.isBlank(idempotencyKey)) {
            log.warn("Rejected create URL request: missing Idempotency-Key header");
            return RequestUtils.errorResponse(TylinkResultCode.SHORTEN_IDEMPOTENCY_KEY_REQUIRED);
        }
        String longUrl = LongUrlValidator.validate(request.getLongUrl());
        if (longUrl == null) {
            log.error("Raw URL: {} is invalid!", request.getLongUrl());
            return RequestUtils.errorResponse(TylinkResultCode.SHORTEN_INVALID_LONG_URL);
        }

        Visibility visibility = Visibility.parse(request.getVisibility());
        if (visibility == null) {
            log.error("Visibility: {} is invalid!");
            return RequestUtils.errorResponse(TylinkResultCode.SHORTEN_INVALID_VISIBILITY);
        }

        String ownerId = AuthUtils.extractOwnerId(input);
        log.info("ownerId: {}", ownerId);
        // Public access cannot create private urls
        if (visibility == Visibility.PRIVATE && ownerId == null) {
            log.warn("Rejected create URL request: PRIVATE visibility requires an authenticated caller");
            return RequestUtils.errorResponse(TylinkResultCode.SHORTEN_PRIVATE_CREATE_UNAUTHORIZED);
        }

        return createUrl(idempotencyKey, longUrl, ownerId, visibility);
    }

    private APIGatewayV2HTTPResponse createUrl(String idempotencyKey, String longUrl, String ownerId, Visibility visibility) {
        try {
            IdempotentCreateRequest request = new IdempotentCreateRequest(idempotencyKey, longUrl, ownerId, visibility);
            return createUrlIdempotent(request);
        } catch (IdempotencyValidationException e) {
            log.warn("Rejected create URL request: Idempotency-Key reused with a different longUrl");
            return RequestUtils.errorResponse(TylinkResultCode.SHORTEN_IDEMPOTENCY_KEY_REUSED);
        } catch (IdempotencyAlreadyInProgressException e) {
            log.warn("Rejected create URL request: duplicate in-flight request for this Idempotency-Key");
            return RequestUtils.errorResponse(TylinkResultCode.SHORTEN_IDEMPOTENCY_IN_PROGRESS);
        } catch (UrlRepositoryException e) {
            log.error("Failed to write short url", e);
            return RequestUtils.errorResponse(TylinkResultCode.SHORTEN_FAILED);
        }
    }

    @Idempotent
    APIGatewayV2HTTPResponse createUrlIdempotent(IdempotentCreateRequest request) {
        // First line deliberately: @Idempotent short-circuits this method body entirely on a
        // cache hit, so reaching this line at all means it's a miss — see 11-emf-metrics.md.
        metrics.addMetric("IdempotencyMiss", 1, MetricUnit.COUNT);

        ShortUrl shortUrl = saveShortUrlWithRetry(request);

        String visibility = Optional.ofNullable(request.visibility()).map(Enum::name).orElse(null);
        return RequestUtils.successResponse(201, Map.of("shortCode", shortUrl.shortCode(), "visibility", visibility));
    }

    private ShortUrl saveShortUrlWithRetry(IdempotentCreateRequest request) {
        ShortUrl shortUrl = null;
        boolean saved = false;
        int attempts = 0;
        while (!saved && attempts < MAX_SHORT_CODE_ATTEMPTS) {
            shortUrl = ShortUrl.create(ShortCodeUtils.generate(), request.longUrl(), request.ownerId(), request.visibility());
            saved = urlRepository.save(shortUrl);
            attempts++;
        }
        metrics.addMetric("ShortCodeCollisionRetries", attempts - 1, MetricUnit.COUNT);
        if (!saved) {
            throw new UrlRepositoryException(
                    "Exhausted " + MAX_SHORT_CODE_ATTEMPTS + " short-code collision retries");
        }

        log.info("Created shortCode={} visibility={} anonymous={} attempts={}",
                shortUrl.shortCode(), request.visibility(), request.ownerId() == null, attempts);
        return shortUrl;
    }
}
