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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.annotations.SdkPublicApi;

/**
 * Thrown when a MODIFY event changes an immutable group-by key. Stream projections cannot
 * move an item between aggregate buckets without a dual-write policy (deferred).
 */
@SdkPublicApi
public final class GroupKeyMutationException extends ProjectionException {

    private final String projectionName;
    private final List<String> changedFields;
    private final Map<String, Object> prevGroupKey;
    private final Map<String, Object> nextGroupKey;

    public GroupKeyMutationException(String projectionName,
                                     List<String> changedFields,
                                     Map<String, Object> prevGroupKey,
                                     Map<String, Object> nextGroupKey) {
        super("projection \"" + projectionName + "\": MODIFY changed groupBy field(s) "
              + changedFields + "; group keys are immutable in this PoC");
        this.projectionName = projectionName;
        this.changedFields = Collections.unmodifiableList(changedFields);
        this.prevGroupKey = Collections.unmodifiableMap(prevGroupKey);
        this.nextGroupKey = Collections.unmodifiableMap(nextGroupKey);
    }

    public String projectionName() {
        return projectionName;
    }

    public List<String> changedFields() {
        return changedFields;
    }

    public Map<String, Object> prevGroupKey() {
        return prevGroupKey;
    }

    public Map<String, Object> nextGroupKey() {
        return nextGroupKey;
    }
}
