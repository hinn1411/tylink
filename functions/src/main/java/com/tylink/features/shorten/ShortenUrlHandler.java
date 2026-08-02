package com.tylink.features.shorten;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.tylink.auth.AuthUtils;
import com.tylink.features.shorten.model.ShortenUrlRequest;
import com.tylink.features.shorten.util.LongUrlValidator;
import com.tylink.features.shorten.util.ShortUrlGenerator;
import com.tylink.model.ShortUrl;
import com.tylink.model.Visibility;
import com.tylink.repository.DynamoDbUrlRepository;
import com.tylink.repository.UrlRepository;
import com.tylink.repository.UrlRepositoryException;
import com.tylink.util.RequestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.utils.StringUtils;
import software.amazon.lambda.powertools.logging.Logging;

import java.util.Map;
import java.util.Optional;

public class ShortenUrlHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LogManager.getLogger(ShortenUrlHandler.class);

    private final UrlRepository urlRepository;

    public ShortenUrlHandler() {
        this(new DynamoDbUrlRepository(DynamoDbClient.create(), System.getenv("TABLE_NAME")));
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
        Optional<String> longUrl = LongUrlValidator.validate(request.getLongUrl());
        if (longUrl.isEmpty()) {
            log.error("Raw URL: {} is invalid!", request.getLongUrl());
            return RequestUtils.jsonResponse(400, Map.of("message", "longUrl must be a valid http or https URL"));
        }

        Optional<Visibility> visibility = Visibility.parse(request.getVisibility());
        if (visibility.isEmpty()) {
            log.error("Visibility: {} is invalid!");
            return RequestUtils.jsonResponse(400, Map.of("message", "visibility must be PUBLIC or PRIVATE"));
        }

        Optional<String> ownerId = AuthUtils.extractOwnerId(input);
        log.info("ownerId: {}", ownerId);
        // Public access cannot create private urls
        if (visibility.get() == Visibility.PRIVATE && ownerId.isEmpty()) {
            log.warn("Rejected create URL request: PRIVATE visibility requires an authenticated caller");
            return RequestUtils.jsonResponse(401, Map.of("message", "unauthorized"));
        }

        return createUrl(longUrl.get(), ownerId, visibility.get());
    }

    private APIGatewayV2HTTPResponse createUrl(String longUrl, Optional<String> ownerId, Visibility visibility) {
        ShortUrl shortUrl = ShortUrl.create(ShortUrlGenerator.generate(), longUrl, ownerId.orElse(null), visibility);
        try {
            urlRepository.save(shortUrl);
        } catch (UrlRepositoryException e) {
            log.error("Failed to write shortCode={} to DynamoDB", shortUrl.shortCode(), e);
            return RequestUtils.jsonResponse(500, Map.of("message", "failed to create short url"));
        }

        log.info("Created shortCode={} visibility={} anonymous={}", shortUrl.shortCode(), visibility, ownerId.isEmpty());
        return RequestUtils.jsonResponse(201, Map.of("shortCode", shortUrl.shortCode(), "visibility", visibility.name()));
    }
}
