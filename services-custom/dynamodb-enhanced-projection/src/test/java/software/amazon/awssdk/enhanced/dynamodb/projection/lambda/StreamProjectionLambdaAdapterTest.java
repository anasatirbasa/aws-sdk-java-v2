/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package software.amazon.awssdk.enhanced.dynamodb.projection.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.enhanced.dynamodb.projection.AggregateDefinition;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionApplicator;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionSpec;
import software.amazon.awssdk.enhanced.dynamodb.projection.Projections;
import software.amazon.awssdk.enhanced.dynamodb.projection.StreamProjectionRuntime;
import software.amazon.awssdk.enhanced.dynamodb.projection.TargetTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class StreamProjectionLambdaAdapterTest {
    @Test
    public void reportsTheFirstFailedStreamSequenceNumber() {
        ProjectionSpec projection = Projections.builder("OrdersByCustomer")
                                                .sourceEntityType("Order")
                                                .groupBy("customerId")
                                                .target(TargetTable.of("OrdersByCustomer", "customerId"))
                                                .field("orderCount", AggregateDefinition.count())
                                                .build();
        ProjectionApplicator applicator = ProjectionApplicator.builder()
                                                                .client(Mockito.mock(DynamoDbClient.class))
                                                                .projection(projection)
                                                                .build();
        StreamProjectionRuntime runtime = StreamProjectionRuntime.builder()
                                                                 .registerAggregate("registered-source", applicator)
                                                                 .build();
        StreamProjectionLambdaAdapter adapter = new StreamProjectionLambdaAdapter(runtime);
        DynamodbEvent event = new DynamodbEvent();
        DynamodbEvent.DynamodbStreamRecord record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventSourceARN("unregistered-source");
        record.setEventID("event-id");
        record.setDynamodb(new StreamRecord().withSequenceNumber("42"));
        event.setRecords(Collections.singletonList(record));

        assertThat(adapter.handle(event).getBatchItemFailures())
            .extracting(failure -> failure.getItemIdentifier())
            .containsExactly("42");
    }

    @Test
    public void reportsMalformedRecordsAsPartialFailures() {
        ProjectionSpec projection = Projections.builder("OrdersByCustomer")
                                                .sourceEntityType("Order")
                                                .groupBy("customerId")
                                                .target(TargetTable.of("OrdersByCustomer", "customerId"))
                                                .field("orderCount", AggregateDefinition.count())
                                                .build();
        ProjectionApplicator applicator = ProjectionApplicator.builder()
                                                                .client(Mockito.mock(DynamoDbClient.class))
                                                                .projection(projection)
                                                                .build();
        StreamProjectionRuntime runtime = StreamProjectionRuntime.builder()
                                                                 .registerAggregate("registered-source", applicator)
                                                                 .build();
        DynamodbEvent event = new DynamodbEvent();
        DynamodbEvent.DynamodbStreamRecord record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventID("event-id");
        record.setDynamodb(new StreamRecord().withSequenceNumber("43"));
        event.setRecords(Collections.singletonList(record));

        assertThat(new StreamProjectionLambdaAdapter(runtime).handle(event).getBatchItemFailures())
            .extracting(failure -> failure.getItemIdentifier())
            .containsExactly("43");
    }
}
