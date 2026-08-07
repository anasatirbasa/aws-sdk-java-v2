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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.projection.AggregateDefinition;
import software.amazon.awssdk.enhanced.dynamodb.projection.DynamoDbSummaryTableReader;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionApplicator;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionExecutionMode;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionSpec;
import software.amazon.awssdk.enhanced.dynamodb.projection.Projections;
import software.amazon.awssdk.enhanced.dynamodb.projection.StreamRecordDecoder;
import software.amazon.awssdk.enhanced.dynamodb.projection.SummaryPage;
import software.amazon.awssdk.enhanced.dynamodb.projection.TargetTable;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

public class DynamoDbSummaryTableReaderIT extends ProjectionLocalDynamoDbTestBase {

    private static final String TABLE = "OrdersByCustomerReaderIT";

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
            .field("totalAmount", AggregateDefinition.sum("amount"))
            .build();
        applicator = ProjectionApplicator.builder()
            .client(client)
            .projection(spec)
            .sourcePartitionKey("customerId")
            .sourceSortKey("orderId")
            .build();
        reader = DynamoDbSummaryTableReader.builder()
            .client(client)
            .projection(spec)
            .tableName(TABLE)
            .executionMode(ProjectionExecutionMode.ALLOW_SCAN)
            .consistentRead(true)
            .build();
        for (int c = 1; c <= 5; c++) {
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("customerId", "c" + c);
            order.put("orderId", "o1");
            order.put("amount", c * 10);
            applicator.applyRecord(StreamRecordDecoder.insert("Order", "c" + c + "#o1", order));
        }
    }

    @Test
    void scanPageRoundTrip() {
        SummaryPage page1 = reader.scanPage(2, null);
        assertThat(page1.rows()).hasSize(2);
        assertThat(page1.cursor()).isNotNull();

        SummaryPage page2 = reader.scanPage(2, page1.cursor());
        assertThat(page2.rows()).hasSize(2);
    }

    @Test
    void batchGetItemsReturnsRequestedKeys() {
        List<Map<String, AttributeValue>> keys = Arrays.asList(
            Collections.singletonMap("customerId", AttributeValue.builder().s("c1").build()),
            Collections.singletonMap("customerId", AttributeValue.builder().s("c3").build()));
        assertThat(reader.batchGetItems(keys)).hasSize(2);
    }

    @Test
    void consistentReadGetItem() {
        assertThat(reader.getItem(Collections.singletonMap(
            "customerId", AttributeValue.builder().s("c2").build()))).isPresent();
        assertThat(reader.consistentRead()).isTrue();
    }
}
