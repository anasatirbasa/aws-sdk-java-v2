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
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

public class DynamoDbProjectionBackfillTest {
    @Test
    public void scansAndAppliesExistingItems() {
        DynamoDbClient client = Mockito.mock(DynamoDbClient.class);
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(UpdateItemResponse.builder().build());
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("id", AttributeValue.builder().s("o1").build());
        item.put("customerId", AttributeValue.builder().s("c1").build());
        item.put("entityType", AttributeValue.builder().s("Order").build());
        item.put("_v", AttributeValue.builder().s("01HBACKFILL").build());
        when(client.scan(any(ScanRequest.class))).thenReturn(
            ScanResponse.builder().items(Collections.singletonList(item)).build());

        ProjectionSpec projection = Projections.builder("OrdersByCustomer")
                                                .sourceEntityType("Order")
                                                .groupBy("customerId")
                                                .target(TargetTable.of("OrdersByCustomer", "customerId"))
                                                .field("count", AggregateDefinition.count())
                                                .build();
        ProjectionApplicator applicator = ProjectionApplicator.builder().client(client).projection(projection).build();

        DynamoDbProjectionBackfill.Result result = DynamoDbProjectionBackfill.builder()
                                                                              .client(client)
                                                                              .applicator(applicator)
                                                                              .sourceTableName("Orders")
                                                                              .build()
                                                                              .execute();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        Mockito.verify(client).updateItem(any(UpdateItemRequest.class));
    }
}
