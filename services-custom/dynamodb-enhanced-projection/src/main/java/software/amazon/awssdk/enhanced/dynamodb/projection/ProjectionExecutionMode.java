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

import software.amazon.awssdk.annotations.SdkPublicApi;

/**
 * Safety gate for Scan operations in the projection PoC (same names as Enhanced Queries
 * {@code ExecutionMode}, different surface).
 *
 * <ul>
 *   <li>{@link #STRICT_KEY_ONLY} — summary GetItem/key Query/GSI Query only; MIN/MAX recompute
 *       via partition Query only (no empty-{@code groupBy} source Scan)</li>
 *   <li>{@link #ALLOW_SCAN} — permits summary Scan / {@link SummaryQuery} and full-table
 *       source Scan for global MIN/MAX recompute</li>
 * </ul>
 */
@SdkPublicApi
public enum ProjectionExecutionMode {
    STRICT_KEY_ONLY,
    ALLOW_SCAN
}
