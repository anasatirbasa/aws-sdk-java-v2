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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

public class ProjectionBatchWriterTest {

    @AfterEach
    public void resetMetrics() {
        ProjectionWriteMetrics.reset();
    }

    @Test
    public void retriesUnprocessedWritesAndReportsCompleteOutcome() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("customerId", AttributeValue.builder().s("c1").build());
        Map<String, List<WriteRequest>> unprocessed = Collections.singletonMap(
            "summary", Collections.singletonList(WriteRequest.builder().build()));
        when(client.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(
            BatchWriteItemResponse.builder().unprocessedItems(unprocessed).build(),
            BatchWriteItemResponse.builder().unprocessedItems(Collections.emptyMap()).build());

        ProjectionBatchWriter.BatchWriteStats stats = ProjectionBatchWriter.batchPutItems(
            client, "summary", Collections.singletonList(item), null);

        assertThat(stats.requestedWrites()).isEqualTo(1L);
        assertThat(stats.requestCount()).isEqualTo(2L);
        assertThat(stats.retryCount()).isEqualTo(1L);
        assertThat(stats.unprocessedWrites()).isZero();
        assertThat(ProjectionWriteMetrics.retryCount()).isEqualTo(1L);
        assertThat(ProjectionWriteMetrics.unprocessedWriteCount()).isZero();
        assertThat(ProjectionWriteMetrics.successfulWriteCount()).isEqualTo(1L);
    }
}
