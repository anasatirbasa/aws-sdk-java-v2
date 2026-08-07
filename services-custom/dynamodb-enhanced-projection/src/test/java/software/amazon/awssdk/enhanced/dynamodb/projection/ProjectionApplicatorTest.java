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
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

public class ProjectionApplicatorTest {

    @BeforeEach
    public void resetMetricsBeforeTest() {
        ProjectionWriteMetrics.reset();
    }

    @AfterEach
    public void resetMetrics() {
        ProjectionWriteMetrics.reset();
    }

    @Test
    public void applyRecordsWriteCapacityAndRequests() {
        DynamoDbClient client = Mockito.mock(DynamoDbClient.class);
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(
            UpdateItemResponse.builder()
                              .consumedCapacity(ConsumedCapacity.builder().capacityUnits(1.5).build())
                              .build());
        ProjectionSpec projection = Projections.builder("OrdersByCustomer")
                                                .sourceEntityType("Order")
                                                .groupBy("customerId")
                                                .target(TargetTable.of("OrdersByCustomer", "customerId"))
                                                .field("orderCount", AggregateDefinition.count())
                                                .build();
        ProjectionApplicator applicator = ProjectionApplicator.builder()
                                                                .client(client)
                                                                .projection(projection)
                                                                .build();

        applicator.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", Collections.singletonMap(
            "customerId", "c1")));

        assertThat(ProjectionWriteMetrics.requestCount()).isEqualTo(1L);
        assertThat(ProjectionWriteMetrics.writeCapacityUnits()).isEqualTo(1.5);
        ArgumentCaptor<UpdateItemRequest> request = ArgumentCaptor.forClass(UpdateItemRequest.class);
        Mockito.verify(client).updateItem(request.capture());
        assertThat(request.getValue().returnConsumedCapacity()).isEqualTo(ReturnConsumedCapacity.TOTAL);
    }

    @Test
    public void streamDecoderRejectsMissingSourceVersion() {
        java.util.Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> image =
            new java.util.LinkedHashMap<>();
        image.put("id", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s("o1").build());
        software.amazon.awssdk.services.dynamodb.model.Record record =
            software.amazon.awssdk.services.dynamodb.model.Record.builder()
                .eventName("INSERT")
                .dynamodb(software.amazon.awssdk.services.dynamodb.model.StreamRecord.builder()
                    .keys(java.util.Collections.singletonMap("id",
                        software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s("o1").build()))
                    .newImage(image)
                    .build())
                .build();

        assertThatThrownBy(() -> StreamRecordDecoder.decode(record, "Order", "id", null))
            .isInstanceOf(ProjectionException.class)
            .hasMessageContaining("missing required version");
    }
}
