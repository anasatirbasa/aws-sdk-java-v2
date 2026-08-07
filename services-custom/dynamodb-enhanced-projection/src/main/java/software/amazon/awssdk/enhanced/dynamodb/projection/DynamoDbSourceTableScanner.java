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
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.utils.Validate;

/**
 * {@link SourceGroupScanner} that Scans the entire source table. Used for MIN/MAX recompute
 * when {@code groupBy} is empty (global {@code ALL} bucket). Requires
 * {@link ProjectionExecutionMode#ALLOW_SCAN}.
 */
@SdkPublicApi
public final class DynamoDbSourceTableScanner implements SourceGroupScanner {

    private final DynamoDbClient client;
    private final String sourceTableName;

    public DynamoDbSourceTableScanner(DynamoDbClient client, String sourceTableName) {
        this.client = Validate.paramNotNull(client, "client");
        this.sourceTableName = Validate.paramNotBlank(sourceTableName, "sourceTableName");
    }

    @Override
    public List<Map<String, Object>> loadGroup(ProjectionSpec projection,
                                               Map<String, Object> groupKeyValues) {
        if (!projection.groupBy().isEmpty()) {
            throw new ProjectionException(
                "DynamoDbSourceTableScanner is only for empty groupBy; use DynamoDbSourceGroupScanner");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            ScanRequest.Builder sb = ScanRequest.builder().tableName(sourceTableName);
            if (startKey != null) {
                sb.exclusiveStartKey(startKey);
            }
            ScanResponse response = client.scan(sb.build());
            for (Map<String, AttributeValue> item : response.items()) {
                out.add(AttributeValueMaps.fromAttributeMap(item));
            }
            startKey = response.lastEvaluatedKey() == null || response.lastEvaluatedKey().isEmpty()
                       ? null : response.lastEvaluatedKey();
        } while (startKey != null);
        return out;
    }
}
