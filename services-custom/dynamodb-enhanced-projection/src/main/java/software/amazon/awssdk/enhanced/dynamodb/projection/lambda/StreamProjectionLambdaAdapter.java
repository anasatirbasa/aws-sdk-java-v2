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

package software.amazon.awssdk.enhanced.dynamodb.projection.lambda;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionBatchResult;
import software.amazon.awssdk.enhanced.dynamodb.projection.StreamProjectionRuntime;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Record;

/**
 * Adapter for a DynamoDB Streams Lambda handler.
 *
 * <p>The adapter stops at the first failed record and returns the sequence number in the Lambda
 * partial-batch response. Configure the event-source mapping with
 * {@code ReportBatchItemFailures}.</p>
 */
@SdkPublicApi
public final class StreamProjectionLambdaAdapter {
    private final StreamProjectionRuntime runtime;

    public StreamProjectionLambdaAdapter(StreamProjectionRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Handles one Lambda batch. Applications can delegate to this method from their Lambda entry
     * point after constructing the runtime with the source stream ARN registrations.
     */
    public StreamsEventResponse handle(DynamodbEvent event) {
        if (event == null || event.getRecords() == null || event.getRecords().isEmpty()) {
            return success();
        }
        for (DynamodbEvent.DynamodbStreamRecord record : event.getRecords()) {
            try {
                ProjectionBatchResult result = runtime.handle(record.getEventSourceARN(),
                                                               Collections.singletonList(convert(record)));
                if (!result.successful()) {
                    return failure(result.firstFailedItemIdentifier());
                }
            } catch (RuntimeException e) {
                return failure(itemIdentifier(record));
            }
        }
        return success();
    }

    private static StreamsEventResponse success() {
        return StreamsEventResponse.builder().withBatchItemFailures(Collections.emptyList()).build();
    }

    private static StreamsEventResponse failure(String itemIdentifier) {
        StreamsEventResponse.BatchItemFailure failure = new StreamsEventResponse.BatchItemFailure();
        failure.setItemIdentifier(itemIdentifier);
        return StreamsEventResponse.builder().withBatchItemFailures(Collections.singletonList(failure)).build();
    }

    private static Record convert(DynamodbEvent.DynamodbStreamRecord input) {
        StreamRecord source = input.getDynamodb();
        software.amazon.awssdk.services.dynamodb.model.StreamRecord.Builder dynamodb =
            software.amazon.awssdk.services.dynamodb.model.StreamRecord.builder();
        if (source != null) {
            dynamodb.keys(convertMap(source.getKeys()))
                    .newImage(convertMap(source.getNewImage()))
                    .oldImage(convertMap(source.getOldImage()))
                    .sequenceNumber(source.getSequenceNumber());
        }
        return Record.builder()
                     .eventID(input.getEventID())
                     .eventName(input.getEventName())
                     .dynamodb(dynamodb.build())
                     .build();
    }

    private static String itemIdentifier(DynamodbEvent.DynamodbStreamRecord record) {
        StreamRecord dynamodb = record.getDynamodb();
        if (dynamodb != null && dynamodb.getSequenceNumber() != null) {
            return dynamodb.getSequenceNumber();
        }
        return record.getEventID();
    }

    private static Map<String, AttributeValue> convertMap(
        Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, AttributeValue> result = new LinkedHashMap<>();
        for (Map.Entry<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> entry
            : source.entrySet()) {
            result.put(entry.getKey(), convertValue(entry.getValue()));
        }
        return result;
    }

    private static AttributeValue convertValue(
        com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue source) {
        AttributeValue.Builder target = AttributeValue.builder();
        if (source.getS() != null) {
            return target.s(source.getS()).build();
        }
        if (source.getN() != null) {
            return target.n(source.getN()).build();
        }
        if (source.getB() != null) {
            return target.b(SdkBytes.fromByteBuffer(copy(source.getB()))).build();
        }
        if (source.getBOOL() != null) {
            return target.bool(source.getBOOL()).build();
        }
        if (Boolean.TRUE.equals(source.getNULL())) {
            return target.nul(true).build();
        }
        if (source.getM() != null) {
            return target.m(convertMap(source.getM())).build();
        }
        if (source.getL() != null) {
            List<AttributeValue> values = new ArrayList<>();
            for (com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue value : source.getL()) {
                values.add(convertValue(value));
            }
            return target.l(values).build();
        }
        if (source.getSS() != null) {
            return target.ss(source.getSS()).build();
        }
        if (source.getNS() != null) {
            return target.ns(source.getNS()).build();
        }
        if (source.getBS() != null) {
            List<SdkBytes> values = new ArrayList<>();
            for (ByteBuffer value : source.getBS()) {
                values.add(SdkBytes.fromByteBuffer(copy(value)));
            }
            return target.bs(values).build();
        }
        return target.nul(true).build();
    }

    private static ByteBuffer copy(ByteBuffer source) {
        ByteBuffer copy = source.asReadOnlyBuffer();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return ByteBuffer.wrap(bytes);
    }
}
