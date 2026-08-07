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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.utils.Validate;

/**
 * Applies a {@link JoinProjectionSpec} to stream records by issuing Put/Delete/Update against
 * the join target table.
 */
@SdkPublicApi
public final class JoinProjectionApplicator {

    private final DynamoDbClient client;
    private final JoinProjectionSpec projection;
    private final ParentResolver parentResolver;
    private final String leftPartitionKeyAttr;
    private final String rightPartitionKeyAttr;
    private final String rightSortKeyAttr;
    private final boolean batchWrites;
    private final ExecutorService batchExecutor;
    private final int maxFanOut;

    private JoinProjectionApplicator(Builder builder) {
        this.client = Validate.paramNotNull(builder.client, "client");
        this.projection = Validate.paramNotNull(builder.projection, "projection");
        this.parentResolver = Validate.paramNotNull(builder.parentResolver, "parentResolver");
        this.leftPartitionKeyAttr = Validate.paramNotBlank(builder.leftPartitionKeyAttr,
                                                           "leftPartitionKeyAttr");
        this.rightPartitionKeyAttr = Validate.paramNotBlank(builder.rightPartitionKeyAttr,
                                                            "rightPartitionKeyAttr");
        this.rightSortKeyAttr = builder.rightSortKeyAttr;
        this.batchWrites = builder.batchWrites;
        this.batchExecutor = builder.batchExecutor;
        this.maxFanOut = builder.maxFanOut;
    }

    public static Builder builder() {
        return new Builder();
    }

    public JoinApplyOutcome applyRecord(NormalizedRecord record) {
        assertImmutableJoinKeys(record);
        List<Map<String, AttributeValue>> existingKeys = JoinProjectionApplyEngine.needsSiblingKeys(projection, record)
                                           ? queryJoinKeysForRecord(record)
                                           : new ArrayList<>();
        if (existingKeys.size() > maxFanOut) {
            throw new ProjectionException("join projection \"" + projection.name() + "\": fan-out of "
                                          + existingKeys.size() + " exceeds configured maximum " + maxFanOut);
        }
        JoinApplyOutcome outcome = JoinProjectionApplyEngine.plan(
            projection, record, parentResolver, existingKeys);
        if (outcome.kind() == JoinApplyOutcome.Kind.SKIPPED) {
            return outcome;
        }
        try {
            ProjectionBatchWriter.executeJoinWrites(client, (JoinApplyOutcome.Writes) outcome,
                                                    batchWrites, batchExecutor);
            return outcome;
        } catch (ConditionalCheckFailedException e) {
            return JoinApplyOutcome.skipped(ApplyOutcome.SkipReason.ALREADY_APPLIED);
        }
    }

    /**
     * Decodes a Streams record then {@link #applyRecord(NormalizedRecord)}.
     */
    public JoinApplyOutcome applyStreamRecord(Record streamRecord, String defaultEntityType) {
        boolean left = projection.leftEntityType().equals(defaultEntityType);
        NormalizedRecord normalized = StreamRecordDecoder.decode(
            streamRecord,
            defaultEntityType,
            left ? leftPartitionKeyAttr : rightPartitionKeyAttr,
            left ? null : rightSortKeyAttr);
        return applyRecord(normalized);
    }

    public JoinProjectionSpec projection() {
        return projection;
    }

    public void close() {
        ProjectionBatchWriter.shutdownQuietly(batchExecutor);
    }

    private List<Map<String, AttributeValue>> queryJoinKeysForRecord(NormalizedRecord record) {
        Map<String, Object> image = record.activeImage();
        if (image == null) {
            return new ArrayList<>();
        }
        Object joinVal;
        if (projection.leftEntityType().equals(record.entityType())) {
            joinVal = image.get(projection.leftJoinAttribute());
        } else if (projection.rightEntityType().equals(record.entityType())) {
            joinVal = image.get(projection.rightJoinAttribute());
        } else {
            return new ArrayList<>();
        }
        if (joinVal == null) {
            return new ArrayList<>();
        }
        return queryJoinKeys(joinVal);
    }

    private void assertImmutableJoinKeys(NormalizedRecord record) {
        if (record.eventName() != NormalizedRecord.EventName.MODIFY || record.prev() == null || record.next() == null) {
            return;
        }
        if (projection.leftEntityType().equals(record.entityType())) {
            assertUnchanged(record, projection.leftJoinAttribute());
        } else if (projection.rightEntityType().equals(record.entityType())) {
            assertUnchanged(record, projection.rightJoinAttribute());
            assertUnchanged(record, projection.rightSortKeyAttribute());
        }
    }

    private void assertUnchanged(NormalizedRecord record, String attribute) {
        if (!java.util.Objects.equals(record.prev().get(attribute), record.next().get(attribute))) {
            throw new JoinKeyMutationException(projection.name(), attribute);
        }
    }

    private List<Map<String, AttributeValue>> queryJoinKeys(Object joinVal) {
        List<Map<String, AttributeValue>> keys = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryResponse response = client.query(QueryRequest.builder()
                                                              .tableName(projection.target().tableName())
                                                              .keyConditionExpression("#pk = :pk")
                                                              .expressionAttributeNames(
                                                                  java.util.Collections.singletonMap(
                                                                      "#pk", projection.target().partitionKey()))
                                                              .expressionAttributeValues(
                                                                  java.util.Collections.singletonMap(
                                                                      ":pk",
                                                                      AttributeValueMaps.toAttributeValue(joinVal)))
                                                              .exclusiveStartKey(startKey)
                                                              .build());
            for (Map<String, AttributeValue> item : response.items()) {
                Map<String, AttributeValue> key = new java.util.LinkedHashMap<>();
                key.put(projection.target().partitionKey(),
                        item.get(projection.target().partitionKey()));
                key.put(projection.target().sortKey(), item.get(projection.target().sortKey()));
                keys.add(key);
            }
            startKey = response.lastEvaluatedKey();
            if (startKey != null && startKey.isEmpty()) {
                startKey = null;
            }
        } while (startKey != null);
        return keys;
    }

    public static final class Builder {
        private DynamoDbClient client;
        private JoinProjectionSpec projection;
        private ParentResolver parentResolver;
        private String leftPartitionKeyAttr;
        private String rightPartitionKeyAttr;
        private String rightSortKeyAttr;
        private boolean batchWrites;
        private ExecutorService batchExecutor;
        private int maxFanOut = 100;

        public Builder client(DynamoDbClient client) {
            this.client = client;
            return this;
        }

        public Builder projection(JoinProjectionSpec projection) {
            this.projection = projection;
            return this;
        }

        public Builder parentResolver(ParentResolver parentResolver) {
            this.parentResolver = parentResolver;
            return this;
        }

        public Builder leftPartitionKey(String leftPartitionKeyAttr) {
            this.leftPartitionKeyAttr = leftPartitionKeyAttr;
            return this;
        }

        public Builder rightPartitionKey(String rightPartitionKeyAttr) {
            this.rightPartitionKeyAttr = rightPartitionKeyAttr;
            return this;
        }

        public Builder rightSortKey(String rightSortKeyAttr) {
            this.rightSortKeyAttr = rightSortKeyAttr;
            return this;
        }

        public Builder batchWrites(boolean batchWrites) {
            this.batchWrites = batchWrites;
            return this;
        }

        public Builder batchExecutor(ExecutorService batchExecutor) {
            this.batchExecutor = batchExecutor;
            return this;
        }

        /** Limits synchronous parent-change fan-out to a bounded number of target rows. */
        public Builder maxFanOut(int maxFanOut) {
            this.maxFanOut = maxFanOut;
            return this;
        }

        public JoinProjectionApplicator build() {
            if (projection != null) {
                if (leftPartitionKeyAttr == null) {
                    leftPartitionKeyAttr = projection.leftJoinAttribute();
                }
                if (rightPartitionKeyAttr == null) {
                    rightPartitionKeyAttr = projection.rightJoinAttribute();
                }
                if (rightSortKeyAttr == null) {
                    rightSortKeyAttr = projection.rightSortKeyAttribute();
                }
            }
            Validate.paramNotBlank(leftPartitionKeyAttr, "leftPartitionKeyAttr");
            Validate.paramNotBlank(rightPartitionKeyAttr, "rightPartitionKeyAttr");
            Validate.paramNotBlank(rightSortKeyAttr, "rightSortKeyAttr");
            if (maxFanOut < 1) {
                throw new IllegalArgumentException("maxFanOut must be positive");
            }
            return new JoinProjectionApplicator(this);
        }
    }
}
