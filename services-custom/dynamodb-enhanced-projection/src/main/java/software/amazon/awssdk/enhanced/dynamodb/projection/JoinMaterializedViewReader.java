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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;
import software.amazon.awssdk.utils.Validate;

/**
 * Reads join materialized-view rows from DynamoDB with Query pagination.
 */
@SdkPublicApi
public final class JoinMaterializedViewReader {

    private final DynamoDbClient client;
    private final JoinProjectionSpec projection;
    private final String tableName;
    private final boolean consistentRead;
    private final AtomicLong benchmarkRequestCount = new AtomicLong();
    private final DoubleAdder benchmarkReadCapacityUnits = new DoubleAdder();

    private JoinMaterializedViewReader(Builder builder) {
        this.client = Validate.paramNotNull(builder.client, "client");
        this.projection = Validate.paramNotNull(builder.projection, "projection");
        this.tableName = Validate.paramNotBlank(builder.tableName, "tableName");
        this.consistentRead = builder.consistentRead;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Queries one partition of the join MV with optional limit and LEK cursor.
     */
    public JoinProjectionHarness.JoinPage queryPage(String partitionKeyValue, Integer limit, String cursor) {
        Validate.paramNotBlank(partitionKeyValue, "partitionKeyValue");
        int pageLimit = limit == null || limit <= 0 ? 100 : limit;
        Map<String, AttributeValue> startKey = null;
        if (cursor != null && !cursor.isEmpty()) {
            startKey = ProjectionCursors.decodeExclusiveStartKey(cursor);
        }

        QueryRequest.Builder queryBuilder = QueryRequest.builder()
                                                          .tableName(tableName)
                                                          .consistentRead(consistentRead)
                                                          .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                                                          .keyConditionExpression("#pk = :pk")
                                                          .expressionAttributeNames(
                                                              Collections.singletonMap(
                                                                  "#pk", projection.target().partitionKey()))
                                                          .expressionAttributeValues(
                                                              Collections.singletonMap(
                                                                  ":pk",
                                                                  AttributeValueMaps.toAttributeValue(partitionKeyValue)))
                                                          .limit(pageLimit);
        if (startKey != null) {
            queryBuilder.exclusiveStartKey(startKey);
        }

        QueryResponse response = client.query(queryBuilder.build());
        benchmarkRequestCount.incrementAndGet();
        if (response.consumedCapacity() != null && response.consumedCapacity().capacityUnits() != null) {
            benchmarkReadCapacityUnits.add(response.consumedCapacity().capacityUnits());
        }
        List<Map<String, AttributeValue>> rows = new ArrayList<>(response.items());
        String next = response.lastEvaluatedKey() == null || response.lastEvaluatedKey().isEmpty()
                      ? null
                      : ProjectionCursors.encodeExclusiveStartKey(response.lastEvaluatedKey());
        return new JoinProjectionHarness.JoinPage(rows, next);
    }

    public long benchmarkRequestCount() {
        return benchmarkRequestCount.get();
    }

    public double benchmarkReadCapacityUnits() {
        return benchmarkReadCapacityUnits.sum();
    }

    public void resetBenchmarkMetrics() {
        benchmarkRequestCount.set(0L);
        benchmarkReadCapacityUnits.reset();
    }

    public static final class Builder {
        private DynamoDbClient client;
        private JoinProjectionSpec projection;
        private String tableName;
        private boolean consistentRead;

        public Builder client(DynamoDbClient client) {
            this.client = client;
            return this;
        }

        public Builder projection(JoinProjectionSpec projection) {
            this.projection = projection;
            return this;
        }

        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public Builder consistentRead(boolean consistentRead) {
            this.consistentRead = consistentRead;
            return this;
        }

        public JoinMaterializedViewReader build() {
            return new JoinMaterializedViewReader(this);
        }
    }
}
