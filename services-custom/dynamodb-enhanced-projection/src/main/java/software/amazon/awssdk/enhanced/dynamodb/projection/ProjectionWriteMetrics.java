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

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity;

/** Metrics used by the projection benchmark to report live write cost. */
@SdkInternalApi
public final class ProjectionWriteMetrics {
    private static final AtomicLong REQUEST_COUNT = new AtomicLong();
    private static final AtomicLong RETRY_COUNT = new AtomicLong();
    private static final AtomicLong UNPROCESSED_WRITE_COUNT = new AtomicLong();
    private static final AtomicLong SUCCESSFUL_WRITE_COUNT = new AtomicLong();
    private static final DoubleAdder WRITE_CAPACITY_UNITS = new DoubleAdder();

    private ProjectionWriteMetrics() {
    }

    public static void record(ConsumedCapacity capacity) {
        REQUEST_COUNT.incrementAndGet();
        if (capacity != null && capacity.capacityUnits() != null) {
            WRITE_CAPACITY_UNITS.add(capacity.capacityUnits());
        }
    }

    public static long requestCount() {
        return REQUEST_COUNT.get();
    }

    public static double writeCapacityUnits() {
        return WRITE_CAPACITY_UNITS.sum();
    }

    /** Number of BatchWriteItem retry requests issued by the current benchmark phase. */
    public static long retryCount() {
        return RETRY_COUNT.get();
    }

    /** Number of writes left unprocessed after retry exhaustion in the current benchmark phase. */
    public static long unprocessedWriteCount() {
        return UNPROCESSED_WRITE_COUNT.get();
    }

    public static void recordRetry() {
        RETRY_COUNT.incrementAndGet();
    }

    public static void recordUnprocessed(long count) {
        if (count > 0) {
            UNPROCESSED_WRITE_COUNT.addAndGet(count);
        }
    }

    public static void recordSuccessfulWrites(long count) {
        if (count > 0) {
            SUCCESSFUL_WRITE_COUNT.addAndGet(count);
        }
    }

    public static long successfulWriteCount() {
        return SUCCESSFUL_WRITE_COUNT.get();
    }

    public static void reset() {
        REQUEST_COUNT.set(0L);
        RETRY_COUNT.set(0L);
        UNPROCESSED_WRITE_COUNT.set(0L);
        SUCCESSFUL_WRITE_COUNT.set(0L);
        WRITE_CAPACITY_UNITS.reset();
    }
}
