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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.utils.Validate;

/**
 * {@link SourceGroupScanner} that Queries the source table by the first group-by attribute
 * (partition key) and filters remaining group-by dimensions in memory.
 */
@SdkPublicApi
public final class DynamoDbSourceGroupScanner implements SourceGroupScanner {

    private final DynamoDbClient client;
    private final String sourceTableName;
    private final String sourcePartitionKeyAttr;

    public DynamoDbSourceGroupScanner(DynamoDbClient client,
                                      String sourceTableName,
                                      String sourcePartitionKeyAttr) {
        this.client = Validate.paramNotNull(client, "client");
        this.sourceTableName = Validate.paramNotBlank(sourceTableName, "sourceTableName");
        this.sourcePartitionKeyAttr = Validate.paramNotBlank(sourcePartitionKeyAttr,
                                                             "sourcePartitionKeyAttr");
    }

    @Override
    public List<Map<String, Object>> loadGroup(ProjectionSpec projection,
                                               Map<String, Object> groupKeyValues) {
        if (projection.groupBy().isEmpty()) {
            throw new ProjectionException(
                "MIN/MAX recompute with empty groupBy requires a full table scan; "
                + "use DynamoDbSourceTableScanner with ProjectionExecutionMode.ALLOW_SCAN");
        }
        Object pk = groupKeyValues.get(projection.groupBy().get(0));
        if (pk == null) {
            throw new ProjectionException("missing group key " + projection.groupBy().get(0));
        }

        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest.Builder qb = QueryRequest.builder()
                .tableName(sourceTableName)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(mapOf("#pk", sourcePartitionKeyAttr))
                .expressionAttributeValues(mapOf(":pk", AttributeValueMaps.toAttributeValue(pk)));
            if (startKey != null) {
                qb.exclusiveStartKey(startKey);
            }
            QueryResponse response = client.query(qb.build());
            for (Map<String, AttributeValue> item : response.items()) {
                Map<String, Object> plain = AttributeValueMaps.fromAttributeMap(item);
                if (matchesGroup(projection, groupKeyValues, plain)) {
                    out.add(plain);
                }
            }
            startKey = response.lastEvaluatedKey() == null || response.lastEvaluatedKey().isEmpty()
                       ? null : response.lastEvaluatedKey();
        } while (startKey != null);
        return out;
    }

    private static boolean matchesGroup(ProjectionSpec projection,
                                        Map<String, Object> groupKeyValues,
                                        Map<String, Object> item) {
        for (String field : projection.groupBy()) {
            if (!Objects.equals(groupKeyValues.get(field), item.get(field))) {
                return false;
            }
        }
        return true;
    }

    private static <K, V> Map<K, V> mapOf(K k, V v) {
        Map<K, V> m = new HashMap<>();
        m.put(k, v);
        return m;
    }
}
