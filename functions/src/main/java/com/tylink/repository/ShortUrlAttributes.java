package com.tylink.repository;

public final class ShortUrlAttributes {

    public static final String PK = "PK";
    public static final String SK = "SK";
    public static final String LONG_URL = "longUrl";
    public static final String VISIBILITY = "visibility";
    public static final String STATUS = "status";
    public static final String CREATED_AT = "createdAt";
    public static final String OWNER_ID = "ownerId";
    public static final String GSI1_PK = "GSI1_PK";
    public static final String GSI1_SK = "GSI1_SK";
    public static final String GSI1_INDEX_NAME = "GSI1";

    public static final String SK_METADATA = "METADATA";

    public static final String URL_KEY_PREFIX = "URL#";
    public static final String USER_KEY_PREFIX = "USER#";

    private ShortUrlAttributes() {
    }
}
