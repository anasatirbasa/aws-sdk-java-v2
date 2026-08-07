/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.enhanced.dynamodb.projection;

import java.util.Map;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.utils.Validate;

/**
 * Explicit, single-threaded historical backfill for an aggregate projection.
 *
 * <p>This class intentionally does not create infrastructure or coordinate a stream consumer.
 * For a race-free cutover, start the stream consumer from a recorded point, scan/backfill the
 * source table, then let the consumer drain records after that point. Source items must already
 * contain stable {@code _v} values.</p>
 */
@SdkPublicApi
public final class DynamoDbProjectionBackfill {
    private final DynamoDbClient client;
    private final ProjectionApplicator applicator;
    private final String sourceTableName;
    private final String sourcePartitionKey;
    private final String sourceSortKey;
    private final int pageSize;
    private final boolean consistentRead;

    private DynamoDbProjectionBackfill(Builder builder) {
        this.client = Validate.paramNotNull(builder.client, "client");
        this.applicator = Validate.paramNotNull(builder.applicator, "applicator");
        this.sourceTableName = Validate.paramNotBlank(builder.sourceTableName, "sourceTableName");
        this.sourcePartitionKey = Validate.paramNotBlank(builder.sourcePartitionKey, "sourcePartitionKey");
        this.sourceSortKey = builder.sourceSortKey;
        this.pageSize = builder.pageSize;
        this.consistentRead = builder.consistentRead;
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("pageSize must be between 1 and 1000");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Scans the current source table and applies each item as an INSERT projection event. */
    public Result execute() {
        Map<String, AttributeValue> exclusiveStartKey = null;
        long scanned = 0;
        long applied = 0;
        long skipped = 0;
        do {
            ScanResponse response = client.scan(ScanRequest.builder()
                                                            .tableName(sourceTableName)
                                                            .limit(pageSize)
                                                            .consistentRead(consistentRead)
                                                            .exclusiveStartKey(exclusiveStartKey)
                                                            .build());
            for (Map<String, AttributeValue> item : response.items()) {
                scanned++;
                ApplyOutcome outcome = applicator.applyRecord(toInsert(item));
                if (outcome.kind() == ApplyOutcome.Kind.APPLIED) {
                    applied++;
                } else {
                    skipped++;
                }
            }
            exclusiveStartKey = response.lastEvaluatedKey();
        } while (exclusiveStartKey != null && !exclusiveStartKey.isEmpty());
        return new Result(scanned, applied, skipped);
    }

    private NormalizedRecord toInsert(Map<String, AttributeValue> attributes) {
        Map<String, Object> item = AttributeValueMaps.fromAttributeMap(attributes);
        Object version = item.get(StreamRecordDecoder.VERSION_ATTR);
        if (version == null) {
            throw new ProjectionException("backfill item is missing required version attribute "
                                          + StreamRecordDecoder.VERSION_ATTR);
        }
        String entityType = item.containsKey(StreamRecordDecoder.ENTITY_TYPE_ATTR)
                            ? String.valueOf(item.get(StreamRecordDecoder.ENTITY_TYPE_ATTR))
                            : applicator.projection().sourceEntityType();
        return NormalizedRecord.builder()
                               .entityType(entityType)
                               .eventName(NormalizedRecord.EventName.INSERT)
                               .next(item)
                               .sourceItemKey(sourceKey(attributes))
                               .sourceVersion(String.valueOf(version))
                               .eventId("backfill-" + sourceKey(attributes))
                               .build();
    }

    private String sourceKey(Map<String, AttributeValue> attributes) {
        AttributeValue partition = attributes.get(sourcePartitionKey);
        if (partition == null) {
            throw new ProjectionException("backfill item is missing partition key " + sourcePartitionKey);
        }
        StringBuilder result = new StringBuilder(keyValue(partition));
        if (sourceSortKey != null) {
            AttributeValue sort = attributes.get(sourceSortKey);
            if (sort == null) {
                throw new ProjectionException("backfill item is missing sort key " + sourceSortKey);
            }
            result.append('#').append(keyValue(sort));
        }
        return result.toString();
    }

    private static String keyValue(AttributeValue value) {
        if (value.s() != null) {
            return value.s();
        }
        if (value.n() != null) {
            return value.n();
        }
        throw new ProjectionException("backfill supports only String and Number source keys");
    }

    /** Summary returned after all scan pages were applied. */
    @SdkPublicApi
    public static final class Result {
        private final long scanned;
        private final long applied;
        private final long skipped;

        private Result(long scanned, long applied, long skipped) {
            this.scanned = scanned;
            this.applied = applied;
            this.skipped = skipped;
        }

        public long scanned() {
            return scanned;
        }

        public long applied() {
            return applied;
        }

        public long skipped() {
            return skipped;
        }
    }

    public static final class Builder {
        private DynamoDbClient client;
        private ProjectionApplicator applicator;
        private String sourceTableName;
        private String sourcePartitionKey = "id";
        private String sourceSortKey;
        private int pageSize = 100;
        private boolean consistentRead;

        public Builder client(DynamoDbClient client) {
            this.client = client;
            return this;
        }

        public Builder applicator(ProjectionApplicator applicator) {
            this.applicator = applicator;
            return this;
        }

        public Builder sourceTableName(String sourceTableName) {
            this.sourceTableName = sourceTableName;
            return this;
        }

        public Builder sourcePartitionKey(String sourcePartitionKey) {
            this.sourcePartitionKey = sourcePartitionKey;
            return this;
        }

        public Builder sourceSortKey(String sourceSortKey) {
            this.sourceSortKey = sourceSortKey;
            return this;
        }

        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public Builder consistentRead(boolean consistentRead) {
            this.consistentRead = consistentRead;
            return this;
        }

        public DynamoDbProjectionBackfill build() {
            return new DynamoDbProjectionBackfill(this);
        }
    }
}
