# Why DynamoDB `AttributeDefinitions` Only Lists PK, SK, GSI1_PK, GSI1_SK

## Context

`UrlTable` items actually carry more attributes than that — `longUrl`,
`ownerId`, `visibility`, `status`, `createdAt`, `expiresAt`, `deletedAt`,
`purgeAt` (see `../technical_decisions/04-dynamodb-access-patterns.md`'s
Conclusion). None of those appear in `template.yaml`'s
`AttributeDefinitions`. That's not an oversight, and it's not optional.

## Why

DynamoDB (and its CloudFormation resource, `AWS::DynamoDB::Table`) is
schemaless outside of keys. `AttributeDefinitions` isn't a column list —
it's the type declaration for whichever attributes are used as a **key**
somewhere: the base table's `PK`/`SK`, or a GSI's/LSI's own key
attributes. Nothing else may be declared there, and nothing else needs to
be — items in the same table can each carry a different, arbitrary set of
non-key attributes with no upfront declaration, unlike a SQL
`CREATE TABLE` column list.

CloudFormation enforces this directly: declaring an attribute in
`AttributeDefinitions` that isn't referenced by the table's `KeySchema` or
any `GlobalSecondaryIndexes`/`LocalSecondaryIndexes` entry fails the
deploy —

```
Number of attributes in KeySchema does not exactly match number of
attributes in AttributeDefinitions
```

## Takeaway

`AttributeDefinitions` lists exactly the four attributes used as keys
somewhere in `UrlTable`: `PK`/`SK` (base table) and `GSI1_PK`/`GSI1_SK`
(GSI1) — and nothing more. Every other item attribute is written directly
by `ShortUrl.toItem()` (`functions/src/main/java/com/tylink/create/model/ShortUrl.java`)
with no corresponding template change required. Adding a new non-key
attribute to an item never touches `template.yaml`; adding a new *access
pattern* that needs a new index does.
