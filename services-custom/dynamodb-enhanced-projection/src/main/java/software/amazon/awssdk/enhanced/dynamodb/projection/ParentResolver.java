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
import software.amazon.awssdk.annotations.SdkPublicApi;

/**
 * Resolves the left-side (parent) item for a join when applying a right-side
 * (child) stream event. Implementations may use an in-memory store, {@code GetItem}, etc.
 */
@SdkPublicApi
@FunctionalInterface
public interface ParentResolver {

    /**
     * @param joinKeyValue value of the join attribute (e.g. customerId)
     * @return parent item attributes, or {@code null} if the parent is missing
     */
    Map<String, Object> findParent(String joinKeyValue);
}
