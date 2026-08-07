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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.utils.Validate;

/**
 * DynamoDB-backed checkpoint store. Each successful transaction changes the source checkpoint
 * and target summary together, preventing a retry from applying an additive delta twice.
 */
@SdkPublicApi
public final class DynamoDbProjectionStateStore implements ProjectionStateStore {
    public static final String DEFAULT_PARTITION_KEY = "projectionGroup";
    public static final String DEFAULT_SORT_KEY = "sourceItem";

    private final DynamoDbClient client;
    private final String tableName;
    private final String partitionKey;
    private final String sortKey;

    private DynamoDbProjectionStateStore(Builder builder) {
        this.client = Validate.paramNotNull(builder.client, "client");
        this.tableName = Validate.paramNotBlank(builder.tableName, "tableName");
        this.partitionKey = builder.partitionKey == null ? DEFAULT_PARTITION_KEY : builder.partitionKey;
        this.sortKey = builder.sortKey == null ? DEFAULT_SORT_KEY : builder.sortKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ApplyOutcome apply(ProjectionSpec projection, NormalizedRecord record, ApplyOutcome.AppliedPlan plan) {
        try {
            client.transactWriteItems(TransactWriteItemsRequest.builder()
                .transactItems(TransactWriteItem.builder().update(stateUpdate(projection, record, plan)).build(),
                               TransactWriteItem.builder().update(summaryUpdate(projection, plan)).build())
                .clientRequestToken(clientRequestToken(projection, plan))
                .build());
            return ApplyOutcome.applied(plan);
        } catch (TransactionCanceledException e) {
            if (isDuplicateCheckpoint(e)) {
                return ApplyOutcome.skipped(ApplyOutcome.SkipReason.ALREADY_APPLIED);
            }
            throw e;
        }
    }

    private Update stateUpdate(ProjectionSpec projection, NormalizedRecord record, ApplyOutcome.AppliedPlan plan) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("#version", "_sourceVersion");
        names.put("#deleted", "_deleted");
        names.put("#generation", "_projectionGeneration");
        names.put("#source", "_sourceIdentity");
        names.put("#target", "_targetIdentity");
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        values.put(":version", AttributeValue.builder().s(plan.effectiveVersion()).build());
        values.put(":deleted", AttributeValue.builder().bool(record.eventName() == NormalizedRecord.EventName.REMOVE).build());
        values.put(":generation", AttributeValue.builder().s(projection.generation()).build());
        values.put(":source", AttributeValue.builder().s(plan.sourceItemKey()).build());
        values.put(":target", AttributeValue.builder().s(canonicalKey(plan.targetKey())).build());
        return Update.builder()
            .tableName(tableName)
            .key(stateKey(projection, plan))
            .updateExpression("SET #version = :version, #deleted = :deleted, #generation = :generation, "
                              + "#source = :source, #target = :target")
            .conditionExpression("attribute_not_exists(#version) OR #version < :version")
            .expressionAttributeNames(names)
            .expressionAttributeValues(values)
            .build();
    }

    private Update summaryUpdate(ProjectionSpec projection, ApplyOutcome.AppliedPlan plan) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("#owner", "_owner");
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        values.put(":owner", AttributeValue.builder().s(projection.name()).build());
        StringBuilder expression = new StringBuilder();
        int index = 0;
        if (!plan.additiveDeltas().isEmpty()) {
            expression.append("ADD ");
            for (Map.Entry<String, Number> entry : plan.additiveDeltas().entrySet()) {
                String name = "#a" + index;
                String value = ":a" + index;
                names.put(name, entry.getKey());
                values.put(value, AttributeValueMaps.toAttributeValue(entry.getValue()));
                if (index > 0) {
                    expression.append(", ");
                }
                expression.append(name).append(' ').append(value);
                index++;
            }
            expression.append(' ');
        }
        expression.append("SET #owner = if_not_exists(#owner, :owner)");
        return Update.builder()
            .tableName(projection.target().tableName())
            .key(plan.targetKey())
            .updateExpression(expression.toString())
            .conditionExpression("attribute_not_exists(#owner) OR #owner = :owner")
            .expressionAttributeNames(names)
            .expressionAttributeValues(values)
            .build();
    }

    private Map<String, AttributeValue> stateKey(ProjectionSpec projection, ApplyOutcome.AppliedPlan plan) {
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        String targetIdentity = canonicalKey(plan.targetKey());
        key.put(partitionKey, AttributeValue.builder()
                                           .s(projection.name() + "#" + projection.generation() + "#" + digest(targetIdentity))
                                           .build());
        key.put(sortKey, AttributeValue.builder().s(digest(plan.sourceItemKey())).build());
        return key;
    }

    private static String canonicalKey(Map<String, AttributeValue> key) {
        StringBuilder result = new StringBuilder();
        for (String name : sorted(key)) {
            result.append(name.length()).append(':').append(name).append('=');
            appendValue(result, key.get(name));
            result.append(';');
        }
        return result.toString();
    }

    private static List<String> sorted(Map<String, AttributeValue> values) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>(values.keySet());
        Collections.sort(names);
        return names;
    }

    private static void appendValue(StringBuilder target, AttributeValue value) {
        if (value.s() != null) {
            target.append("S:").append(value.s().length()).append(':').append(value.s());
        } else if (value.n() != null) {
            target.append("N:").append(value.n());
        } else if (value.bool() != null) {
            target.append("B:").append(value.bool());
        } else if (value.nul() != null) {
            target.append("NULL");
        } else {
            target.append(value);
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JRE", e);
        }
    }

    private static String clientRequestToken(ProjectionSpec projection, ApplyOutcome.AppliedPlan plan) {
        return digest(projection.name() + "#" + projection.generation() + "#" + plan.sourceItemKey()
                      + "#" + plan.effectiveVersion()).substring(0, 32);
    }

    private static boolean isDuplicateCheckpoint(TransactionCanceledException error) {
        if (!error.hasCancellationReasons() || error.cancellationReasons().size() < 2) {
            return false;
        }
        CancellationReason checkpoint = error.cancellationReasons().get(0);
        CancellationReason summary = error.cancellationReasons().get(1);
        return "ConditionalCheckFailed".equals(checkpoint.code()) && !isConditionalFailure(summary);
    }

    private static boolean isConditionalFailure(CancellationReason reason) {
        return reason != null && "ConditionalCheckFailed".equals(reason.code());
    }

    public static final class Builder {
        private DynamoDbClient client;
        private String tableName;
        private String partitionKey;
        private String sortKey;

        public Builder client(DynamoDbClient client) {
            this.client = client;
            return this;
        }

        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public Builder partitionKey(String partitionKey) {
            this.partitionKey = partitionKey;
            return this;
        }

        public Builder sortKey(String sortKey) {
            this.sortKey = sortKey;
            return this;
        }

        public DynamoDbProjectionStateStore build() {
            return new DynamoDbProjectionStateStore(this);
        }
    }
}
