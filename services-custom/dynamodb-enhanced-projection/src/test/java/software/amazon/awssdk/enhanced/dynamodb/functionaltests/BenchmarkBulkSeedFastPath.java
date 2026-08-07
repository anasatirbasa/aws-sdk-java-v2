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

package software.amazon.awssdk.enhanced.dynamodb.functionaltests;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import software.amazon.awssdk.enhanced.dynamodb.projection.AggregateDefinition;
import software.amazon.awssdk.enhanced.dynamodb.projection.BenchmarkSharedJoinRowStore;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjections;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionSpec;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionApplyEngine;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinType;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionBatchWriter;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionSpec;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionWriteMetrics;
import software.amazon.awssdk.enhanced.dynamodb.projection.TargetTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Benchmark-only bulk loader: precomputes summary and join MV rows (S10) without stream simulation.
 */
final class BenchmarkBulkSeedFastPath {

    private static final int SUMMARY_FLUSH_CHUNK_SIZE = 1_000;
    private static final int DEFAULT_DDB_WRITE_PARALLELISM = 8;
    private static final long DEFAULT_DDB_WRITE_PAUSE_MS = 0L;
    private static final AttributeValue BULK_VERSION = AttributeValue.builder().s("bulk").build();
    private static final JoinProjectionSpec BENCHMARK_JOIN_SPEC = joinSpec(JoinType.INNER);

    private BenchmarkBulkSeedFastPath() {
    }

    static long seedMainDatasetInMemory(BenchmarkBulkSeeder.SeedContext ctx) {
        long start = System.nanoTime();
        BenchmarkSharedJoinRowStore sharedJoinRows = new BenchmarkSharedJoinRowStore();
        for (JoinProjectionHarness harness : ctx.joinHarnesses.values()) {
            harness.useSharedJoinRows(sharedJoinRows);
        }
        long totalOrders = (long) ctx.customerCount * ctx.ordersPerCustomer;
        int progressEvery = Math.max(1, ctx.customerCount / 10);
        AtomicLong ordersDone = new AtomicLong();
        BenchmarkBulkSeedMath.OrderStats statsPerCustomer =
            BenchmarkBulkSeedMath.statsForOrders(ctx.ordersPerCustomer);
        CountDownLatch done = new CountDownLatch(ctx.customerCount);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, ctx.parallelism));
        List<Future<?>> futures = new ArrayList<>(ctx.customerCount);

        for (int c = 1; c <= ctx.customerCount; c++) {
            final int customerIndex = c;
            futures.add(pool.submit(() -> {
                try {
                    seedCustomerInMemory(ctx, customerIndex, statsPerCustomer);
                    if (ctx.logProgress) {
                        long applied = ordersDone.addAndGet(ctx.ordersPerCustomer);
                        if (customerIndex == 1 || customerIndex == ctx.customerCount
                            || customerIndex % progressEvery == 0) {
                            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                            double pct = (100.0 * applied) / Math.max(1L, totalOrders);
                            System.out.printf(Locale.ROOT,
                                              "  bulk progress: customer %d/%d (%.1f%% orders), "
                                              + "%,d/%,d orders, %,d ms%n",
                                              customerIndex, ctx.customerCount, pct,
                                              applied, totalOrders, elapsedMs);
                            System.out.flush();
                        }
                    }
                } finally {
                    done.countDown();
                }
            }));
        }

        awaitLatch(done);
        awaitFutures(futures, "in-memory bulk seed");
        shutdownPool(pool);
        loadGlobalSummaryInMemory(ctx);
        return (System.nanoTime() - start) / 1_000_000L;
    }

    static void seedExtensionsInMemory(BenchmarkBulkSeeder.SeedContext ctx) {
        Map<String, Object> orphanCustomer = BenchmarkBulkSeeder.customerRecord(
            BenchmarkBulkSeeder.ORPHAN_CUSTOMER_ID, "OrphanCustomer", "EU");
        for (JoinProjectionHarness harness : ctx.joinHarnesses.values()) {
            harness.loadPrecomputedParent(orphanCustomer);
            JoinType type = harness.projection().joinType();
            if (type == JoinType.LEFT || type == JoinType.FULL) {
                harness.loadPrecomputedJoinRow(leftOnlyItem(orphanCustomer));
            }
        }

        Map<String, Object> orphanOrder = BenchmarkBulkSeeder.orderRecord(
            BenchmarkBulkSeeder.ORPHAN_ORDER_CUSTOMER_ID,
            BenchmarkBulkSeeder.ORPHAN_ORDER_ID,
            999,
            "Unknown",
            "US");
        for (JoinProjectionHarness harness : ctx.joinHarnesses.values()) {
            JoinType type = harness.projection().joinType();
            if (type == JoinType.RIGHT || type == JoinType.FULL) {
                harness.loadPrecomputedJoinRow(joinOrderItem(orphanOrder, null));
            }
        }
    }

    static long seedDynamoDb(BenchmarkBulkSeeder.DynamoSeedContext ctx) {
        long start = System.nanoTime();
        long totalOrders = (long) ctx.customerCount * ctx.ordersPerCustomer;
        BenchmarkBulkSeedMath.OrderStats statsPerCustomer =
            BenchmarkBulkSeedMath.statsForOrders(ctx.ordersPerCustomer);
        int writeParallelism = ctx.writeParallelism > 0
                               ? ctx.writeParallelism
                               : positiveIntEnv("BENCHMARK_DDB_WRITE_PARALLELISM", DEFAULT_DDB_WRITE_PARALLELISM);
        long writePauseMs = ctx.writePauseMs >= 0
                            ? ctx.writePauseMs
                            : nonNegativeLongEnv("BENCHMARK_DDB_WRITE_PAUSE_MS", DEFAULT_DDB_WRITE_PAUSE_MS);

        System.out.printf(Locale.ROOT,
                          "  Seeding summary/region/global + %d join tables in parallel; "
                          + "writeParallelism=%d (per table), pause=%d ms.%n",
                          JoinType.values().length, writeParallelism, writePauseMs);

        // Build summary and region items in parallel
        ConcurrentLinkedQueue<Map<String, AttributeValue>> summaryQueue = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Map<String, AttributeValue>> regionQueue = new ConcurrentLinkedQueue<>();
        ExecutorService buildPool = Executors.newFixedThreadPool(Math.max(1, ctx.parallelism));
        List<Future<?>> buildFutures = new ArrayList<>(ctx.customerCount);
        for (int c = 1; c <= ctx.customerCount; c++) {
            final int customerIndex = c;
            buildFutures.add(buildPool.submit(
                () -> seedSummaryItemsConcurrent(ctx, customerIndex, statsPerCustomer, summaryQueue, regionQueue)));
        }
        awaitFutures(buildFutures, "summary item construction");
        shutdownPool(buildPool);

        List<Map<String, AttributeValue>> summaryItems = new ArrayList<>(summaryQueue);
        List<Map<String, AttributeValue>> regionItems = new ArrayList<>(regionQueue);

        // Flush summary/region/global with a shared pool
        ExecutorService summaryFlushPool = ProjectionBatchWriter.newBatchPool(writeParallelism);
        try {
            BenchmarkBulkSeedMath.OrderStats globalStats =
                BenchmarkBulkSeedMath.globalStats(ctx.customerCount, ctx.ordersPerCustomer);
            flushItems(ctx.client, ctx.globalTable, summaryFlushPool,
                       Collections.singletonList(globalSummaryItem(ctx.globalSpec, globalStats)), 1L, start, ctx.logProgress);
            flushItems(ctx.client, ctx.summaryTable, summaryFlushPool, summaryItems, summaryItems.size(), start, ctx.logProgress);
            flushItems(ctx.client, ctx.regionTable, summaryFlushPool, regionItems, regionItems.size(), start, ctx.logProgress);
        } finally {
            ProjectionBatchWriter.shutdownQuietly(summaryFlushPool);
        }

        // Seed all 4 join tables in parallel, each with its own flush pool
        final int finalWriteParallelism = writeParallelism;
        final long finalWritePauseMs = writePauseMs;
        ExecutorService joinOrchestrationPool = Executors.newFixedThreadPool(JoinType.values().length);
        List<Future<?>> joinFutures = new ArrayList<>(JoinType.values().length);
        for (JoinType type : JoinType.values()) {
            String table = ctx.joinTables.get(type);
            joinFutures.add(joinOrchestrationPool.submit(() -> {
                ExecutorService perTablePool = ProjectionBatchWriter.newBatchPool(finalWriteParallelism);
                try {
                    streamJoinTable(ctx, type, table, perTablePool, totalOrders, start, finalWritePauseMs);
                } finally {
                    ProjectionBatchWriter.shutdownQuietly(perTablePool);
                }
            }));
        }
        awaitFutures(joinFutures, "join table seed");
        shutdownPool(joinOrchestrationPool);

        ctx.customerRegion.put("c1", "APAC");
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private static void seedCustomerInMemory(BenchmarkBulkSeeder.SeedContext ctx,
                                             int customerIndex,
                                             BenchmarkBulkSeedMath.OrderStats statsPerCustomer) {
        String customerId = "c" + customerIndex;
        String region = BenchmarkBulkSeedMath.customerRegion(customerIndex);
        String name = "Customer-" + customerIndex;
        ctx.customerRegion.put(customerId, region);

        Map<String, AttributeValue> carryForward =
            Collections.singletonMap("region", AttributeValue.builder().s(region).build());

        ctx.harnessByCustomer.loadPrecomputedSummary(
            Collections.singletonMap("customerId", AttributeValue.builder().s(customerId).build()),
            customerAggregates(statsPerCustomer),
            carryForward);

        Map<String, AttributeValue> regionKey = new LinkedHashMap<>();
        regionKey.put("customerId", AttributeValue.builder().s(customerId).build());
        regionKey.put("region", AttributeValue.builder().s(region).build());
        ctx.harnessByCustomerRegion.loadPrecomputedSummary(
            regionKey,
            regionAggregates(statsPerCustomer),
            null);

        Map<String, Object> customer = BenchmarkBulkSeeder.customerRecord(customerId, name, region);
        if (customerIndex == 1) {
            customer.put("region", "APAC");
            ctx.customerRegion.put("c1", "APAC");
        }
        for (JoinProjectionHarness harness : ctx.joinHarnesses.values()) {
            harness.loadPrecomputedParent(customer);
        }

        for (int o = 1; o <= ctx.ordersPerCustomer; o++) {
            Map<String, Object> order = BenchmarkBulkSeeder.orderRecord(
                customerId, "o" + o, BenchmarkBulkSeedMath.orderAmount(o), name, region);
            Map<String, AttributeValue> joinRow = joinOrderItem(order, customer);
            for (JoinProjectionHarness harness : ctx.joinHarnesses.values()) {
                harness.loadPrecomputedJoinRow(joinRow);
            }
        }
    }

    static void loadGlobalSummaryInMemory(BenchmarkBulkSeeder.SeedContext ctx) {
        BenchmarkBulkSeedMath.OrderStats global =
            BenchmarkBulkSeedMath.globalStats(ctx.customerCount, ctx.ordersPerCustomer);
        Map<String, Number> aggregates = new LinkedHashMap<>();
        aggregates.put("totalOrders", global.orderCount);
        aggregates.put("totalRevenue", global.totalAmount);
        aggregates.put("minAmount", global.minAmount);
        aggregates.put("maxAmount", global.maxAmount);
        ctx.harnessGlobal.loadPrecomputedSummary(
            Collections.singletonMap("pk", AttributeValue.builder().s("ALL").build()),
            aggregates,
            null);
    }

    private static void seedSummaryItems(BenchmarkBulkSeeder.DynamoSeedContext ctx,
                                         int customerIndex,
                                         BenchmarkBulkSeedMath.OrderStats statsPerCustomer,
                                         List<Map<String, AttributeValue>> summaryItems,
                                         List<Map<String, AttributeValue>> regionItems) {
        String customerId = "c" + customerIndex;
        String region = BenchmarkBulkSeedMath.customerRegion(customerIndex);
        ctx.customerRegion.put(customerId, region);

        Map<String, AttributeValue> carryForward =
            Collections.singletonMap("region", AttributeValue.builder().s(region).build());
        Map<String, AttributeValue> summaryKey =
            Collections.singletonMap("customerId", AttributeValue.builder().s(customerId).build());
        summaryItems.add(summaryItem(ctx.byCustomerSpec, summaryKey, customerAggregates(statsPerCustomer),
                                     carryForward));

        Map<String, AttributeValue> regionKey = new LinkedHashMap<>();
        regionKey.put("customerId", AttributeValue.builder().s(customerId).build());
        regionKey.put("region", AttributeValue.builder().s(region).build());
        regionItems.add(summaryItem(ctx.regionSpec, regionKey, regionAggregates(statsPerCustomer), null));

        if (customerIndex == 1) {
            ctx.customerRegion.put("c1", "APAC");
        }
    }

    private static void seedSummaryItemsConcurrent(BenchmarkBulkSeeder.DynamoSeedContext ctx,
                                                   int customerIndex,
                                                   BenchmarkBulkSeedMath.OrderStats statsPerCustomer,
                                                   ConcurrentLinkedQueue<Map<String, AttributeValue>> summaryQueue,
                                                   ConcurrentLinkedQueue<Map<String, AttributeValue>> regionQueue) {
        String customerId = "c" + customerIndex;
        String region = BenchmarkBulkSeedMath.customerRegion(customerIndex);
        ctx.customerRegion.put(customerId, region);

        Map<String, AttributeValue> carryForward =
            Collections.singletonMap("region", AttributeValue.builder().s(region).build());
        Map<String, AttributeValue> summaryKey =
            Collections.singletonMap("customerId", AttributeValue.builder().s(customerId).build());
        summaryQueue.add(summaryItem(ctx.byCustomerSpec, summaryKey, customerAggregates(statsPerCustomer), carryForward));

        Map<String, AttributeValue> regionKey = new LinkedHashMap<>();
        regionKey.put("customerId", AttributeValue.builder().s(customerId).build());
        regionKey.put("region", AttributeValue.builder().s(region).build());
        regionQueue.add(summaryItem(ctx.regionSpec, regionKey, regionAggregates(statsPerCustomer), null));

        if (customerIndex == 1) {
            ctx.customerRegion.put("c1", "APAC");
        }
    }

    private static void streamJoinTable(BenchmarkBulkSeeder.DynamoSeedContext ctx,
                                        JoinType type,
                                        String tableName,
                                        ExecutorService flushPool,
                                        long totalOrders,
                                        long start,
                                        long writePauseMs) {
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalArgumentException("Missing target table for " + type + " join benchmark");
        }
        System.out.printf(Locale.ROOT, "  Materializing %s join target %s (%,d rows)...%n",
                          type, tableName, totalOrders + extensionRowCount(type));
        long beforeWrites = ProjectionWriteMetrics.successfulWriteCount();
        long beforeRetries = ProjectionWriteMetrics.retryCount();
        int progressEvery = Math.max(1, ctx.customerCount / 20);
        for (int c = 1; c <= ctx.customerCount; c++) {
            String customerId = "c" + c;
            String region = BenchmarkBulkSeedMath.customerRegion(c);
            String name = "Customer-" + c;
            Map<String, Object> customer = BenchmarkBulkSeeder.customerRecord(customerId, name, region);
            if (c == 1) {
                customer.put("region", "APAC");
            }
            List<Map<String, AttributeValue>> rows = new ArrayList<>(ctx.ordersPerCustomer);
            for (int o = 1; o <= ctx.ordersPerCustomer; o++) {
                Map<String, Object> order = BenchmarkBulkSeeder.orderRecord(
                    customerId, "o" + o, BenchmarkBulkSeedMath.orderAmount(o), name, region);
                rows.add(joinOrderItem(order, customer));
            }
            flushItems(ctx.client, tableName, flushPool, rows, totalOrders, start, false);
            if (writePauseMs > 0L) {
                sleep(writePauseMs);
            }
            if (ctx.logProgress && (c == 1 || c == ctx.customerCount || c % progressEvery == 0)) {
                logTargetProgress(tableName, c * (long) ctx.ordersPerCustomer, totalOrders,
                                  beforeWrites, beforeRetries, start);
            }
        }
        flushJoinExtensions(ctx, type, tableName, flushPool, totalOrders, start);
        if (ctx.logProgress) {
            logTargetProgress(tableName, totalOrders + extensionRowCount(type), totalOrders + extensionRowCount(type),
                              beforeWrites, beforeRetries, start);
        }
    }

    private static long extensionRowCount(JoinType type) {
        return (type == JoinType.LEFT || type == JoinType.RIGHT) ? 1L : (type == JoinType.FULL ? 2L : 0L);
    }

    private static void flushJoinExtensions(BenchmarkBulkSeeder.DynamoSeedContext ctx,
                                            JoinType type,
                                            String tableName,
                                            ExecutorService flushPool,
                                            long totalOrders,
                                            long start) {
        List<Map<String, AttributeValue>> rows = new ArrayList<>(2);
        Map<String, Object> orphanCustomer = BenchmarkBulkSeeder.customerRecord(
            BenchmarkBulkSeeder.ORPHAN_CUSTOMER_ID, "OrphanCustomer", "EU");
        Map<String, Object> orphanOrder = BenchmarkBulkSeeder.orderRecord(
            BenchmarkBulkSeeder.ORPHAN_ORDER_CUSTOMER_ID,
            BenchmarkBulkSeeder.ORPHAN_ORDER_ID,
            999,
            "Unknown",
            "US");

        if (type == JoinType.LEFT || type == JoinType.FULL) {
            rows.add(leftOnlyItem(orphanCustomer));
        }
        if (type == JoinType.RIGHT || type == JoinType.FULL) {
            rows.add(joinOrderItem(orphanOrder, null));
        }
        if (!rows.isEmpty()) {
            flushItems(ctx.client, tableName, flushPool, rows, totalOrders + rows.size(), start, false);
        }
    }

    private static void flushItems(DynamoDbClient client,
                                   String tableName,
                                   ExecutorService flushPool,
                                   List<Map<String, AttributeValue>> items,
                                   long totalRows,
                                   long start,
                                   boolean logProgress) {
        if (tableName == null || tableName.isEmpty() || items.isEmpty()) {
            return;
        }
        for (int offset = 0; offset < items.size(); offset += SUMMARY_FLUSH_CHUNK_SIZE) {
            int end = Math.min(items.size(), offset + SUMMARY_FLUSH_CHUNK_SIZE);
            ProjectionBatchWriter.batchPutItems(client, tableName, items.subList(offset, end), flushPool);
            if (logProgress) {
                logTargetProgress(tableName, end, totalRows, 0L, 0L, start);
            }
        }
    }

    private static void awaitFutures(List<Future<?>> futures, String operation) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(operation + " interrupted", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException(operation + " failed", e.getCause());
            }
        }
    }

    private static void logTargetProgress(String tableName,
                                          long writtenRows,
                                          long totalRows,
                                          long initialWrites,
                                          long initialRetries,
                                          long start) {
        long elapsedMs = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
        long successfulWrites = Math.max(0L, ProjectionWriteMetrics.successfulWriteCount() - initialWrites);
        long retries = Math.max(0L, ProjectionWriteMetrics.retryCount() - initialRetries);
        double percentage = (100.0 * writtenRows) / Math.max(1L, totalRows);
        double rowsPerSecond = successfulWrites * 1000.0 / elapsedMs;
        System.out.printf(Locale.ROOT,
                          "    %s: %,d/%,d rows (%.1f%%), %.1f rows/s, retries=%d, elapsed=%,d ms%n",
                          tableName, writtenRows, totalRows, percentage, rowsPerSecond, retries, elapsedMs);
        System.out.flush();
    }

    private static int positiveIntEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return parsed;
    }

    private static long nonNegativeLongEnv(String name, long defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        long parsed = Long.parseLong(value);
        if (parsed < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return parsed;
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DynamoDB benchmark materialization interrupted", e);
        }
    }

    private static JoinProjectionSpec joinSpec(JoinType joinType) {
        return JoinProjections.builder("CustomersOrdersJoin")
                              .joinType(joinType)
                              .leftEntityType("Customer")
                              .rightEntityType("Order")
                              .leftJoinAttribute("customerId")
                              .rightJoinAttribute("customerId")
                              .rightSortKeyAttribute("orderId")
                              .leftFields("name", "region")
                              .rightFields("orderId", "amount")
                              .target(TargetTable.of("CustomersOrdersJoin", "customerId", "orderId"))
                              .build();
    }

    private static Map<String, AttributeValue> summaryItem(ProjectionSpec spec,
                                                           Map<String, AttributeValue> key,
                                                           Map<String, Number> aggregates,
                                                           Map<String, AttributeValue> carryForward) {
        Map<String, AttributeValue> item = new LinkedHashMap<>(key);
        if (carryForward != null) {
            item.putAll(carryForward);
        }
        for (Map.Entry<String, Number> entry : aggregates.entrySet()) {
            item.put(entry.getKey(), numberAttr(entry.getValue()));
        }
        if (aggregates.containsKey("avgAmount")
            && aggregates.containsKey("totalAmount")
            && aggregates.containsKey("orderCount")) {
            item.put(AggregateDefinition.avgSumAttr("avgAmount"),
                     numberAttr(aggregates.get("totalAmount")));
            item.put(AggregateDefinition.avgCountAttr("avgAmount"),
                     numberAttr(aggregates.get("orderCount")));
        }
        spec.aggregateGsi().ifPresent(gsi -> item.put(
            gsi.partitionKeyAttribute(),
            AttributeValue.builder().s(gsi.partitionKeyValue()).build()));
        item.put("_owner", AttributeValue.builder().s(spec.name()).build());
        item.put("_v", BULK_VERSION);
        return item;
    }

    private static Map<String, AttributeValue> globalSummaryItem(ProjectionSpec spec,
                                                                 BenchmarkBulkSeedMath.OrderStats stats) {
        Map<String, AttributeValue> key =
            Collections.singletonMap("pk", AttributeValue.builder().s("ALL").build());
        Map<String, Number> aggregates = new LinkedHashMap<>();
        aggregates.put("totalOrders", stats.orderCount);
        aggregates.put("totalRevenue", stats.totalAmount);
        aggregates.put("minAmount", stats.minAmount);
        aggregates.put("maxAmount", stats.maxAmount);
        return summaryItem(spec, key, aggregates, null);
    }

    private static Map<String, Number> customerAggregates(BenchmarkBulkSeedMath.OrderStats stats) {
        Map<String, Number> aggregates = new LinkedHashMap<>();
        aggregates.put("orderCount", stats.orderCount);
        aggregates.put("totalAmount", stats.totalAmount);
        aggregates.put("avgAmount", stats.avgAmount);
        aggregates.put("minAmount", stats.minAmount);
        aggregates.put("maxAmount", stats.maxAmount);
        aggregates.put("largeOrders", stats.largeOrders);
        aggregates.put("largeRevenue", stats.largeRevenue);
        return aggregates;
    }

    private static Map<String, Number> regionAggregates(BenchmarkBulkSeedMath.OrderStats stats) {
        Map<String, Number> aggregates = new LinkedHashMap<>();
        aggregates.put("orderCount", stats.orderCount);
        aggregates.put("totalAmount", stats.totalAmount);
        return aggregates;
    }

    private static Map<String, AttributeValue> joinOrderItem(Map<String, Object> order,
                                                             Map<String, Object> parent) {
        JoinProjectionSpec spec = BENCHMARK_JOIN_SPEC;
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        Object customerId = order.get("customerId");
        Object orderId = order.get("orderId");
        item.put(spec.target().partitionKey(), AttributeValue.builder().s(String.valueOf(customerId)).build());
        item.put(spec.target().sortKey(), AttributeValue.builder().s(String.valueOf(orderId)).build());
        if (parent != null) {
            for (String field : spec.leftFields()) {
                Object value = parent.get(field);
                if (value != null) {
                    item.put(field, stringOrNumberAttr(value));
                }
            }
        }
        for (String field : spec.rightFields()) {
            Object value = order.get(field);
            if (value != null) {
                item.put(field, stringOrNumberAttr(value));
            }
        }
        item.put("_owner", AttributeValue.builder().s(spec.name()).build());
        item.put("_v", BULK_VERSION);
        item.put("_rightKey", AttributeValue.builder().s(customerId + "#" + orderId).build());
        return item;
    }

    private static Map<String, AttributeValue> leftOnlyItem(Map<String, Object> parent) {
        JoinProjectionSpec spec = BENCHMARK_JOIN_SPEC;
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        Object joinVal = parent.get(spec.leftJoinAttribute());
        item.put(spec.target().partitionKey(), AttributeValue.builder().s(String.valueOf(joinVal)).build());
        item.put(spec.target().sortKey(),
                 AttributeValue.builder().s(JoinProjectionApplyEngine.LEFT_ONLY_SORT_KEY).build());
        for (String field : spec.leftFields()) {
            Object value = parent.get(field);
            if (value != null) {
                item.put(field, stringOrNumberAttr(value));
            }
        }
        item.put("_owner", AttributeValue.builder().s(spec.name()).build());
        item.put("_v", BULK_VERSION);
        item.put("_leftOnly", AttributeValue.builder().bool(true).build());
        return item;
    }

    private static AttributeValue stringOrNumberAttr(Object value) {
        if (value instanceof Number) {
            return numberAttr((Number) value);
        }
        return AttributeValue.builder().s(String.valueOf(value)).build();
    }

    private static AttributeValue numberAttr(Number value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }

    private static void awaitLatch(CountDownLatch done) {
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void shutdownPool(ExecutorService pool) {
        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
