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

/** Receives non-blocking stream-projection runtime events. */
@SdkPublicApi
public interface ProjectionRuntimeObserver {
    ProjectionRuntimeObserver NO_OP = new ProjectionRuntimeObserver() {
    };

    default void onApplied(String projectionName, String eventId) {
    }

    default void onSkipped(String projectionName, String eventId, ApplyOutcome.SkipReason reason) {
    }

    default void onFailure(String sourceArn, String eventId, RuntimeException error) {
    }
}
