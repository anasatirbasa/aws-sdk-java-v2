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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

public class DynamoDbProjectionStateStoreTest {

    @Test
    public void applyWritesStateAndSummaryInOneTransaction() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        ProjectionSpec projection = Projections.builder("OrdersByCustomer")
            .sourceEntityType("Order")
            .groupBy("customerId")
            .target(TargetTable.of("OrdersByCustomer", "customerId"))
            .field("count", AggregateDefinition.count())
            .field("total", AggregateDefinition.sum("amount"))
            .build();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("customerId", "c1");
        item.put("amount", new java.math.BigDecimal("1.25"));
        NormalizedRecord record = StreamRecordDecoder.insert("Order", "c1#o1", item);
        ApplyOutcome.AppliedPlan plan = ((ApplyOutcome.Applied) ProjectionApplyEngine
            .buildApplyRequest(projection, record)).plan();

        ApplyOutcome outcome = DynamoDbProjectionStateStore.builder().client(client).tableName("ProjectionState")
            .build().apply(projection, record, plan);

        assertThat(outcome.kind()).isEqualTo(ApplyOutcome.Kind.APPLIED);
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(request.capture());
        assertThat(request.getValue().transactItems()).hasSize(2);
        assertThat(request.getValue().clientRequestToken()).hasSize(32);
        assertThat(request.getValue().transactItems().get(0).update().tableName()).isEqualTo("ProjectionState");
        assertThat(request.getValue().transactItems().get(1).update().tableName()).isEqualTo("OrdersByCustomer");
    }

    @Test
    public void propagatesNonCheckpointTransactionCancellation() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        TransactionCanceledException collision = TransactionCanceledException.builder()
            .cancellationReasons(CancellationReason.builder().code("None").build(),
                                 CancellationReason.builder().code("ConditionalCheckFailed").build())
            .build();
        org.mockito.Mockito.when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenThrow(collision);
        ProjectionSpec projection = Projections.builder("OrdersByCustomer")
            .sourceEntityType("Order")
            .groupBy("customerId")
            .target(TargetTable.of("OrdersByCustomer", "customerId"))
            .field("count", AggregateDefinition.count())
            .build();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("customerId", "c1");
        NormalizedRecord record = StreamRecordDecoder.insert("Order", "c1#o1", item);
        ApplyOutcome.AppliedPlan plan = ((ApplyOutcome.Applied) ProjectionApplyEngine
            .buildApplyRequest(projection, record)).plan();

        assertThatThrownBy(() -> DynamoDbProjectionStateStore.builder().client(client).tableName("ProjectionState")
            .build().apply(projection, record, plan)).isSameAs(collision);
    }
}
