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
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package software.amazon.awssdk.enhanced.dynamodb.functionaltests;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinType;
import software.amazon.awssdk.enhanced.dynamodb.projection.NormalizedRecord;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionSpec;
import software.amazon.awssdk.enhanced.dynamodb.projection.StreamRecordDecoder;
import software.amazon.awssdk.enhanced.dynamodb.projection.VersionGenerator;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Seeds the benchmark dataset. When {@code fastBulkSeed} is {@code true}, uses closed-form bulk load (S10)
 * instead of per-event {@code applyRecord} simulation.
 */
final class BenchmarkBulkSeeder {

    static final String ORPHAN_CUSTOMER_ID = "c_orphan";
    static final String ORPHAN_ORDER_ID = "o_orphan";
    static final String ORPHAN_ORDER_CUSTOMER_ID = "c_nonexistent";

    private BenchmarkBulkSeeder() {
    }

    static long seedMainDataset(SeedContext ctx) {
        if (ctx.fastBulkSeed) {
            return BenchmarkBulkSeedFastPath.seedMainDatasetInMemory(ctx);
        }
        return seedMainDatasetStreamSimulation(ctx);
    }

    static void seedExtensions(SeedContext ctx) {
        if (ctx.fastBulkSeed) {
            BenchmarkBulkSeedFastPath.seedExtensionsInMemory(ctx);
            return;
        }
        seedExtensionsStreamSimulation(ctx);
    }

    static long seedMainDatasetStreamSimulation(SeedContext ctx) {
        long buildLoopStart = System.nanoTime();
        long totalOrders = (long) ctx.customerCount * ctx.ordersPerCustomer;
        int progressEvery = Math.max(1, ctx.customerCount / 10);
        AtomicLong ordersApplied = new AtomicLong();
        CountDownLatch done = new CountDownLatch(ctx.customerCount);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, ctx.parallelism));

        for (int c = 1; c <= ctx.customerCount; c++) {
            final int customerIndex = c;
            pool.submit(() -> {
                try {
                    seedCustomerAndOrdersStream(ctx, customerIndex);
                    if (ctx.logProgress) {
                        long applied = ordersApplied.addAndGet(ctx.ordersPerCustomer);
                        if (customerIndex == 1 || customerIndex == ctx.customerCount
                            || customerIndex % progressEvery == 0) {
                            if (applied % progressEvery == 0 || applied == totalOrders) {
                                long elapsedMs = (System.nanoTime() - buildLoopStart) / 1_000_000L;
                                double pct = (100.0 * applied) / Math.max(1L, totalOrders);
                                System.out.printf(Locale.ROOT,
                                                  "  progress: customer %d/%d (%.1f%% orders), "
                                                  + "orders applied %,d/%,d, elapsed %,d ms%n",
                                                  customerIndex, ctx.customerCount, pct,
                                                  applied, totalOrders, elapsedMs);
                                System.out.flush();
                            }
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        pool.shutdown();
        BenchmarkBulkSeedFastPath.loadGlobalSummaryInMemory(ctx);
        return (System.nanoTime() - buildLoopStart) / 1_000_000L;
    }

    static void seedExtensionsStreamSimulation(SeedContext ctx) {
        Map<String, Object> orphanCustomer = customerRecord(ORPHAN_CUSTOMER_ID, "OrphanCustomer", "EU");
        NormalizedRecord orphanCustomerInsert =
            StreamRecordDecoder.insert("Customer", ORPHAN_CUSTOMER_ID, orphanCustomer);
        applyToJoinHarnesses(ctx.joinHarnesses, orphanCustomerInsert);

        Map<String, Object> orphanOrder = orderRecord(ORPHAN_ORDER_CUSTOMER_ID, ORPHAN_ORDER_ID, 999,
                                                      "Unknown", "US");
        NormalizedRecord orphanOrderInsert =
            StreamRecordDecoder.insert("Order", ORPHAN_ORDER_CUSTOMER_ID + "#" + ORPHAN_ORDER_ID, orphanOrder);
        applyToJoinHarnesses(ctx.joinHarnesses, orphanOrderInsert);

        String c1RegionBefore = ctx.customerRegion.get("c1");
        Map<String, Object> c1Prev = customerRecord("c1", "Customer-1", c1RegionBefore);
        Map<String, Object> c1Next = customerRecord("c1", "Customer1Modified", "APAC");
        NormalizedRecord c1Modify = StreamRecordDecoder.modify("Customer", "c1", c1Prev, c1Next);
        applyToJoinHarnesses(ctx.joinHarnesses, c1Modify);
        ctx.customerRegion.put("c1", "APAC");
    }

    private static void seedCustomerAndOrdersStream(SeedContext ctx, int customerIndex) {
        String customerId = "c" + customerIndex;
        String region = BenchmarkBulkSeedMath.customerRegion(customerIndex);
        String name = "Customer-" + customerIndex;
        ctx.customerRegion.put(customerId, region);

        Map<String, Object> customer = customerRecord(customerId, name, region);
        NormalizedRecord customerInsert = StreamRecordDecoder.insert("Customer", customerId, customer);
        applyToJoinHarnesses(ctx.joinHarnesses, customerInsert);

        for (int o = 1; o <= ctx.ordersPerCustomer; o++) {
            int amount = BenchmarkBulkSeedMath.orderAmount(o);
            Map<String, Object> order = orderRecord(customerId, "o" + o, amount, name, region);
            String sourceKey = customerId + "#o" + o;
            NormalizedRecord orderInsert = StreamRecordDecoder.insert("Order", sourceKey, order);

            ctx.harnessByCustomer.applyRecord(orderInsert);
            ctx.harnessByCustomerRegion.applyRecord(orderInsert);
            applyToJoinHarnesses(ctx.joinHarnesses, orderInsert);
        }
    }

    private static void applyToJoinHarnesses(Map<JoinType, JoinProjectionHarness> joinHarnesses,
                                             NormalizedRecord record) {
        for (JoinProjectionHarness harness : joinHarnesses.values()) {
            harness.applyRecord(record);
        }
    }

    static Map<String, Object> customerRecord(String customerId, String name, String region) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("customerId", customerId);
        customer.put("name", name);
        customer.put("region", region);
        customer.put("_v", VersionGenerator.next());
        return customer;
    }

    static Map<String, Object> orderRecord(String customerId,
                                           String orderId,
                                           int amount,
                                           String name,
                                           String region) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("customerId", customerId);
        order.put("orderId", orderId);
        order.put("amount", amount);
        order.put("name", name);
        order.put("region", region);
        order.put("status", amount >= 50 ? "paid" : "pending");
        order.put("_v", VersionGenerator.next());
        return order;
    }

    static SeedContext newContext(int customerCount,
                                  int ordersPerCustomer,
                                  int parallelism,
                                  boolean logProgress,
                                  boolean fastBulkSeed,
                                  ProjectionHarness harnessByCustomer,
                                  ProjectionHarness harnessByCustomerRegion,
                                  ProjectionHarness harnessGlobal,
                                  Map<JoinType, JoinProjectionHarness> joinHarnesses) {
        SeedContext ctx = new SeedContext();
        ctx.customerCount = customerCount;
        ctx.ordersPerCustomer = ordersPerCustomer;
        ctx.parallelism = parallelism;
        ctx.logProgress = logProgress;
        ctx.fastBulkSeed = fastBulkSeed;
        ctx.harnessByCustomer = harnessByCustomer;
        ctx.harnessByCustomerRegion = harnessByCustomerRegion;
        ctx.harnessGlobal = harnessGlobal;
        ctx.joinHarnesses = joinHarnesses;
        ctx.customerRegion = new ConcurrentHashMap<>();
        return ctx;
    }

    static final class SeedContext {
        int customerCount;
        int ordersPerCustomer;
        int parallelism;
        boolean logProgress;
        boolean fastBulkSeed;
        ProjectionHarness harnessByCustomer;
        ProjectionHarness harnessByCustomerRegion;
        ProjectionHarness harnessGlobal;
        Map<JoinType, JoinProjectionHarness> joinHarnesses;
        Map<String, String> customerRegion;
    }

    static final class DynamoSeedContext {
        DynamoDbClient client;
        int customerCount;
        int ordersPerCustomer;
        int parallelism;
        String summaryTable;
        String regionTable;
        String globalTable;
        ProjectionSpec byCustomerSpec;
        ProjectionSpec regionSpec;
        ProjectionSpec globalSpec;
        int writeParallelism = -1;
        long writePauseMs = -1L;
        Map<JoinType, String> joinTables = new EnumMap<>(JoinType.class);
        Map<String, String> customerRegion = new ConcurrentHashMap<>();
        boolean logProgress;
    }
}
