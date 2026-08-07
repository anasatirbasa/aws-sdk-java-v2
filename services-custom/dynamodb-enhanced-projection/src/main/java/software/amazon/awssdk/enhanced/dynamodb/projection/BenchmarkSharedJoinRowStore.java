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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Benchmark-only shared join row storage: one physical row map per {@code rowId} across join harnesses.
 */
@SdkInternalApi
public final class BenchmarkSharedJoinRowStore {

    private final Map<String, Map<String, AttributeValue>> rows = new ConcurrentHashMap<>();

    public Map<String, AttributeValue> register(String rowId, Map<String, AttributeValue> row) {
        Map<String, AttributeValue> existing = rows.putIfAbsent(rowId, row);
        return existing != null ? existing : row;
    }

    public Map<String, AttributeValue> get(String rowId) {
        return rows.get(rowId);
    }

    public void remove(String rowId) {
        rows.remove(rowId);
    }
}
