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
 * Join types for stream-projection join materialization. Semantics mirror Enhanced Queries
 * {@code JoinType}, adapted to a write-time materialized view.
 *
 * <ul>
 *   <li>{@link #INNER}: one target row per matching (parent, child) pair. Orphan children and
 *       parent-only rows are omitted.</li>
 *   <li>{@link #LEFT}: every parent appears. Matching children produce one row each; when a
 *       parent has no children, a sentinel left-only row is stored
 *       ({@link JoinProjectionApplyEngine#LEFT_ONLY_SORT_KEY}).</li>
 *   <li>{@link #RIGHT}: every child appears. When the parent is missing, the join row is stored
 *       with empty left attributes.</li>
 *   <li>{@link #FULL}: union of {@link #LEFT} and {@link #RIGHT}.</li>
 * </ul>
 */
@SdkPublicApi
public enum JoinType {
    INNER,
    LEFT,
    RIGHT,
    FULL
}
