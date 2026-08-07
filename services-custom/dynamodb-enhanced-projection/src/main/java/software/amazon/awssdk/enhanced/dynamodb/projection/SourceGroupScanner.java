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

import java.util.List;
import java.util.Map;
import software.amazon.awssdk.annotations.SdkPublicApi;

/**
 * Loads all current source items for a projection group key. Used to recompute MIN/MAX after
 * invalidate events.
 */
@SdkPublicApi
@FunctionalInterface
public interface SourceGroupScanner {

    /**
     * @param projection projection definition
     * @param groupKeyValues group-by field → value from the stream image
     * @return current source items belonging to that group (may be empty)
     */
    List<Map<String, Object>> loadGroup(ProjectionSpec projection, Map<String, Object> groupKeyValues);
}
