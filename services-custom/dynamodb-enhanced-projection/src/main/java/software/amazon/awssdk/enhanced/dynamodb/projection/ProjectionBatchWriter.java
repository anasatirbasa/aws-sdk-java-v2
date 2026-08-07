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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * Shared BatchWriteItem helper for projection applicators (backfill path).
 */
@SdkInternalApi
public final class ProjectionBatchWriter {

    private static final int BATCH_SIZE = 25;
    private static final int MAX_ATTEMPTS = 10;
    private static final long MAX_RETRY_DELAY_MS = 2_000L;

    private ProjectionBatchWriter() {
    }

    public static BatchWriteStats batchPutItems(DynamoDbClient client,
                                                String tableName,
                                                List<Map<String, AttributeValue>> items,
                                                ExecutorService batchExecutor) {
        if (items == null || items.isEmpty()) {
            return BatchWriteStats.empty();
        }
        List<WriteRequest> requests = new ArrayList<>(items.size());
        for (Map<String, AttributeValue> item : items) {
            requests.add(WriteRequest.builder()
                                     .putRequest(PutRequest.builder().item(item).build())
                                     .build());
        }
        return submitBatches(client, tableName, requests, batchExecutor);
    }

    static void executeJoinWrites(DynamoDbClient client,
                                  JoinApplyOutcome.Writes writes,
                                  boolean batchWrites,
                                  ExecutorService batchExecutor) {
        if (!batchWrites) {
            for (JoinApplyOutcome.Write write : writes.writes()) {
                dispatchSingle(client, write);
            }
            return;
        }
        List<WriteRequest> putAndDelete = new ArrayList<>();
        List<JoinApplyOutcome.Write> updates = new ArrayList<>();
        for (JoinApplyOutcome.Write write : writes.writes()) {
            if (write instanceof JoinApplyOutcome.Write.Put) {
                putAndDelete.add(WriteRequest.builder()
                                             .putRequest(PutRequest.builder()
                                                                   .item(((JoinApplyOutcome.Write.Put) write)
                                                                             .request().item())
                                                                   .build())
                                             .build());
            } else if (write instanceof JoinApplyOutcome.Write.Delete) {
                putAndDelete.add(WriteRequest.builder()
                                             .deleteRequest(DeleteRequest.builder()
                                                                         .key(((JoinApplyOutcome.Write.Delete) write)
                                                                                  .request().key())
                                                                         .build())
                                             .build());
            } else if (write instanceof JoinApplyOutcome.Write.Update) {
                updates.add(write);
            }
        }
        submitBatches(client, tableNameFrom(writes), putAndDelete, batchExecutor);
        for (JoinApplyOutcome.Write update : updates) {
            dispatchSingle(client, update);
        }
    }

    private static String tableNameFrom(JoinApplyOutcome.Writes writes) {
        for (JoinApplyOutcome.Write write : writes.writes()) {
            if (write instanceof JoinApplyOutcome.Write.Put) {
                return ((JoinApplyOutcome.Write.Put) write).request().tableName();
            }
            if (write instanceof JoinApplyOutcome.Write.Delete) {
                return ((JoinApplyOutcome.Write.Delete) write).request().tableName();
            }
            if (write instanceof JoinApplyOutcome.Write.Update) {
                return ((JoinApplyOutcome.Write.Update) write).request().tableName();
            }
        }
        throw new ProjectionException("empty join write batch");
    }

    private static BatchWriteStats submitBatches(DynamoDbClient client,
                                                 String tableName,
                                                 List<WriteRequest> requests,
                                                 ExecutorService batchExecutor) {
        if (requests.isEmpty()) {
            return BatchWriteStats.empty();
        }
        List<List<WriteRequest>> chunks = chunk(requests, BATCH_SIZE);
        if (batchExecutor == null) {
            BatchWriteStats total = BatchWriteStats.empty();
            for (List<WriteRequest> chunk : chunks) {
                total = total.plus(flushBatch(client, tableName, chunk));
            }
            return total;
        }
        List<Future<BatchWriteStats>> futures = new ArrayList<>(chunks.size());
        for (List<WriteRequest> chunk : chunks) {
            futures.add(batchExecutor.submit(() -> flushBatch(client, tableName, chunk)));
        }
        BatchWriteStats total = BatchWriteStats.empty();
        for (Future<BatchWriteStats> future : futures) {
            try {
                total = total.plus(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProjectionException("parallel batch write interrupted", e);
            } catch (ExecutionException e) {
                throw new ProjectionException("parallel batch write failed", e.getCause());
            }
        }
        return total;
    }

    private static BatchWriteStats flushBatch(DynamoDbClient client,
                                              String tableName,
                                              List<WriteRequest> chunk) {
        Map<String, List<WriteRequest>> requestItems =
            Collections.singletonMap(tableName, chunk);
        Map<String, List<WriteRequest>> unprocessed = requestItems;
        int attempts = 0;
        int retries = 0;
        long requests = 0L;
        while (unprocessed != null && !unprocessed.isEmpty()) {
            if (attempts++ >= MAX_ATTEMPTS) {
                long count = unprocessed.values().stream().mapToLong(List::size).sum();
                ProjectionWriteMetrics.recordUnprocessed(count);
                throw new ProjectionException("BatchWriteItem for table " + tableName + " left " + count
                                              + " unprocessed writes after " + MAX_ATTEMPTS + " attempts");
            }
            if (attempts > 1) {
                retries++;
                ProjectionWriteMetrics.recordRetry();
                sleepBeforeRetry(attempts - 1);
            }
            BatchWriteItemResponse response = client.batchWriteItem(
                BatchWriteItemRequest.builder()
                                     .requestItems(unprocessed)
                                     .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                                     .build());
            recordWrite(response);
            requests++;
            unprocessed = response.unprocessedItems();
        }
        ProjectionWriteMetrics.recordSuccessfulWrites(chunk.size());
        return new BatchWriteStats(chunk.size(), requests, retries, 0L);
    }

    private static void sleepBeforeRetry(int retryNumber) {
        long baseDelay = Math.min(MAX_RETRY_DELAY_MS, 25L << Math.min(retryNumber - 1, 6));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, baseDelay / 2));
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(baseDelay + jitter));
        if (Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            throw new ProjectionException("BatchWriteItem retry interrupted");
        }
    }

    private static void recordWrite(BatchWriteItemResponse response) {
        if (response != null && response.consumedCapacity() != null) {
            response.consumedCapacity().forEach(ProjectionWriteMetrics::record);
        } else {
            ProjectionWriteMetrics.record(null);
        }
    }

    private static List<List<WriteRequest>> chunk(List<WriteRequest> items, int size) {
        List<List<WriteRequest>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            out.add(items.subList(i, Math.min(items.size(), i + size)));
        }
        return out;
    }

    private static void dispatchSingle(DynamoDbClient client, JoinApplyOutcome.Write write) {
        if (write instanceof JoinApplyOutcome.Write.Put) {
            client.putItem(((JoinApplyOutcome.Write.Put) write).request());
        } else if (write instanceof JoinApplyOutcome.Write.Delete) {
            client.deleteItem(((JoinApplyOutcome.Write.Delete) write).request());
        } else if (write instanceof JoinApplyOutcome.Write.Update) {
            client.updateItem(((JoinApplyOutcome.Write.Update) write).request());
        } else {
            throw new ProjectionException("unsupported join write type: " + write.getClass());
        }
    }

    public static ExecutorService newBatchPool(int parallelism) {
        return Executors.newFixedThreadPool(Math.max(1, parallelism));
    }

    public static void shutdownQuietly(ExecutorService pool) {
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Immutable outcome for one or more BatchWriteItem calls. */
    @SdkInternalApi
    public static final class BatchWriteStats {
        private final long requestedWrites;
        private final long requestCount;
        private final long retryCount;
        private final long unprocessedWrites;

        private BatchWriteStats(long requestedWrites, long requestCount, long retryCount, long unprocessedWrites) {
            this.requestedWrites = requestedWrites;
            this.requestCount = requestCount;
            this.retryCount = retryCount;
            this.unprocessedWrites = unprocessedWrites;
        }

        static BatchWriteStats empty() {
            return new BatchWriteStats(0L, 0L, 0L, 0L);
        }

        BatchWriteStats plus(BatchWriteStats other) {
            return new BatchWriteStats(requestedWrites + other.requestedWrites,
                                       requestCount + other.requestCount,
                                       retryCount + other.retryCount,
                                       unprocessedWrites + other.unprocessedWrites);
        }

        public long requestedWrites() {
            return requestedWrites;
        }

        public long requestCount() {
            return requestCount;
        }

        public long retryCount() {
            return retryCount;
        }

        public long unprocessedWrites() {
            return unprocessedWrites;
        }
    }
}
