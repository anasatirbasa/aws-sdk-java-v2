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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

public class DynamoDbSummaryTableReaderTest {

    private static final ProjectionSpec SPEC = Projections.builder("OrdersByCustomer")
                                                          .sourceEntityType("Order")
                                                          .groupBy("customerId")
                                                          .target(TargetTable.of("OrdersByCustomer", "customerId"))
                                                          .field("orderCount", AggregateDefinition.count())
                                                          .carryForward("region")
                                                          .build();

    @Test
    public void getItemUsesConsistentReadFlag() {
        DynamoDbClient client = Mockito.mock(DynamoDbClient.class);
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder()
            .item(item("c1", "EU", 3))
            .build());

        DynamoDbSummaryTableReader reader = DynamoDbSummaryTableReader.builder()
            .client(client)
            .projection(SPEC)
            .tableName("OrdersByCustomer")
            .consistentRead(true)
            .build();

        Optional<SummaryRow> row = reader.getItem(
            Collections.singletonMap("customerId", AttributeValue.builder().s("c1").build()));
        assertThat(row).isPresent();
        assertThat(row.get().aggregates().get("orderCount").intValue()).isEqualTo(3);
        assertThat(row.get().attributes().get("region")).isEqualTo("EU");

        ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(captor.capture());
        assertThat(captor.getValue().consistentRead()).isTrue();
    }

    @Test
    public void batchGetItemsChunksRequests() {
        DynamoDbClient client = Mockito.mock(DynamoDbClient.class);
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenReturn(
            BatchGetItemResponse.builder()
                                .responses(Collections.singletonMap(
                                    "OrdersByCustomer",
                                    Arrays.asList(item("c1", "EU", 1), item("c2", "US", 2))))
                                .build());

        DynamoDbSummaryTableReader reader = DynamoDbSummaryTableReader.builder()
            .client(client)
            .projection(SPEC)
            .tableName("OrdersByCustomer")
            .build();

        List<Map<String, AttributeValue>> keys = Arrays.asList(
            Collections.singletonMap("customerId", AttributeValue.builder().s("c1").build()),
            Collections.singletonMap("customerId", AttributeValue.builder().s("c2").build()));
        List<SummaryRow> rows = reader.batchGetItems(keys);
        assertThat(rows).hasSize(2);
        verify(client).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    public void batchGetItemsRetriesUnprocessedKeys() {
        DynamoDbClient client = Mockito.mock(DynamoDbClient.class);
        Map<String, AttributeValue> c1 = Collections.singletonMap(
            "customerId", AttributeValue.builder().s("c1").build());
        Map<String, AttributeValue> c2 = Collections.singletonMap(
            "customerId", AttributeValue.builder().s("c2").build());
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenReturn(
            BatchGetItemResponse.builder()
                                .responses(Collections.singletonMap("OrdersByCustomer",
                                                                   Collections.singletonList(item("c1", "EU", 1))))
                                .unprocessedKeys(Collections.singletonMap(
                                    "OrdersByCustomer", KeysAndAttributes.builder().keys(c2).build()))
                                .build(),
            BatchGetItemResponse.builder()
                                .responses(Collections.singletonMap("OrdersByCustomer",
                                                                   Collections.singletonList(item("c2", "US", 2))))
                                .build());

        DynamoDbSummaryTableReader reader = DynamoDbSummaryTableReader.builder()
            .client(client)
            .projection(SPEC)
            .tableName("OrdersByCustomer")
            .build();

        List<SummaryRow> rows = reader.batchGetItems(Arrays.asList(c1, c2));

        assertThat(rows).extracting(row -> row.key().get("customerId"))
                        .containsExactly("c1", "c2");
        verify(client, Mockito.times(2)).batchGetItem(any(BatchGetItemRequest.class));
    }

    private static Map<String, AttributeValue> item(String customerId, String region, int count) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("customerId", AttributeValue.builder().s(customerId).build());
        item.put("region", AttributeValue.builder().s(region).build());
        item.put("orderCount", AttributeValue.builder().n(Integer.toString(count)).build());
        return item;
    }
}
