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

package software.amazon.awssdk.enhanced.dynamodb.functionaltests;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.projection.AggregateDefinition;
import software.amazon.awssdk.enhanced.dynamodb.projection.DynamoDbSummaryTableReader;
import software.amazon.awssdk.enhanced.dynamodb.projection.NormalizedRecord;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionApplicator;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionSpec;
import software.amazon.awssdk.enhanced.dynamodb.projection.Projections;
import software.amazon.awssdk.enhanced.dynamodb.projection.StreamRecordDecoder;
import software.amazon.awssdk.enhanced.dynamodb.projection.TargetTable;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

public class ProjectionApplicatorBatchIT extends ProjectionLocalDynamoDbTestBase {

    private static final String TABLE = "OrdersByCustomerBatchIT";

    private ProjectionApplicator applicator;
    private DynamoDbSummaryTableReader reader;

    @BeforeEach
    void setUp() {
        recreateTable(
            TABLE,
            Collections.singletonList(KeySchemaElement.builder()
                                                      .attributeName("customerId")
                                                      .keyType(KeyType.HASH)
                                                      .build()),
            Collections.singletonList(AttributeDefinition.builder()
                                                         .attributeName("customerId")
                                                         .attributeType(ScalarAttributeType.S)
                                                         .build()));

        ProjectionSpec spec = Projections.builder("OrdersByCustomer")
            .sourceEntityType("Order")
            .groupBy("customerId")
            .target(TargetTable.of(TABLE, "customerId"))
            .field("orderCount", AggregateDefinition.count())
            .build();

        applicator = ProjectionApplicator.builder()
            .client(client)
            .projection(spec)
            .sourcePartitionKey("customerId")
            .sourceSortKey("orderId")
            .batchWrites(true)
            .batchExecutor(Executors.newFixedThreadPool(2))
            .build();
        reader = DynamoDbSummaryTableReader.builder()
            .client(client)
            .projection(spec)
            .tableName(TABLE)
            .consistentRead(true)
            .build();
    }

    @AfterEach
    void tearDown() {
        if (applicator != null) {
            applicator.close();
        }
    }

    @Test
    void applyRecordsParallelBackfillUpdatesSummary() {
        List<NormalizedRecord> records = Arrays.asList(
            StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 1)),
            StreamRecordDecoder.insert("Order", "c2#o1", order("c2", "o1", 2)),
            StreamRecordDecoder.insert("Order", "c3#o1", order("c3", "o1", 3)));
        applicator.applyRecords(records);

        assertThat(reader.getItem(Collections.singletonMap(
            "customerId", AttributeValue.builder().s("c1").build()))).isPresent();
        assertThat(reader.getItem(Collections.singletonMap(
            "customerId", AttributeValue.builder().s("c2").build())).get()
            .aggregates().get("orderCount").intValue()).isEqualTo(1);
        assertThat(reader.getItem(Collections.singletonMap(
            "customerId", AttributeValue.builder().s("c3").build())).get()
            .aggregates().get("orderCount").intValue()).isEqualTo(1);
    }

    @Test
    void batchPutPrecomputedItemsWritesSummaryRows() {
        List<Map<String, AttributeValue>> items = Arrays.asList(
            precomputedSummaryItem("c1", 42),
            precomputedSummaryItem("c2", 10));
        applicator.batchPutPrecomputedItems(items);

        assertThat(reader.getItem(Collections.singletonMap(
            "customerId", AttributeValue.builder().s("c1").build())).get()
            .aggregates().get("orderCount").intValue()).isEqualTo(42);
        assertThat(reader.getItem(Collections.singletonMap(
            "customerId", AttributeValue.builder().s("c2").build())).get()
            .aggregates().get("orderCount").intValue()).isEqualTo(10);
    }

    private static Map<String, AttributeValue> precomputedSummaryItem(String customerId, int orderCount) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("customerId", AttributeValue.builder().s(customerId).build());
        item.put("orderCount", AttributeValue.builder().n(String.valueOf(orderCount)).build());
        item.put("_owner", AttributeValue.builder().s("OrdersByCustomer").build());
        item.put("_v", AttributeValue.builder().s("bulk").build());
        return item;
    }

    private static Map<String, Object> order(String customerId, String orderId, int amount) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("customerId", customerId);
        order.put("orderId", orderId);
        order.put("amount", amount);
        return order;
    }
}
