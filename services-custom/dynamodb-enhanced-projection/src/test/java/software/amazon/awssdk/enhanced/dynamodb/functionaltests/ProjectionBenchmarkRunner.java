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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import software.amazon.awssdk.enhanced.dynamodb.projection.AggregateDefinition;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionSpec;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjections;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinType;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionSpec;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionWriteMetrics;
import software.amazon.awssdk.enhanced.dynamodb.projection.Projections;
import software.amazon.awssdk.enhanced.dynamodb.projection.SortDirection;
import software.amazon.awssdk.enhanced.dynamodb.projection.SummaryPage;
import software.amazon.awssdk.enhanced.dynamodb.projection.SummaryQuery;
import software.amazon.awssdk.enhanced.dynamodb.projection.TargetTable;
import software.amazon.awssdk.enhanced.dynamodb.query.condition.Condition;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Benchmark runner for stream-projection read scenarios, aligned with Enhanced Queries
 * scenario names ({@link BenchmarkScenarioCatalog} / {@code benchmark-scenarios.json}).
 *
 * <p>Default mode uses in-memory {@link ProjectionHarness} and {@link JoinProjectionHarness}
 * instances (no AWS credentials). Optional DynamoDB mode is documented in
 * {@code EC2_BENCHMARK_INSTRUCTIONS.md}.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code CUSTOMER_COUNT} – default 1000</li>
 *   <li>{@code ORDERS_PER_CUSTOMER} – default 1000</li>
 *   <li>{@code BENCHMARK_ITERATIONS} – default 10</li>
 *   <li>{@code BENCHMARK_WARMUP} – default 3</li>
 *   <li>{@code BENCHMARK_OUTPUT_FILE} – optional CSV path</li>
 *   <li>{@code BENCHMARK_BACKEND} – {@code memory} (default) or {@code dynamodb}</li>
 *   <li>{@code BENCHMARK_BULK_SEED} – default {@code false}; {@code true} uses closed-form bulk load
 *       (in-memory or DynamoDB BatchWriteItem) instead of stream simulation</li>
 *   <li>{@code BENCHMARK_DDB_WRITE_PARALLELISM} – write threads per table, 4 join tables run in parallel
 *       (default: 8)</li>
 *   <li>{@code BENCHMARK_DDB_WRITE_PAUSE_MS} – pause between customer batches per join table
 *       (default: 0)</li>
 *   <li>{@code PROJECTION_CONSISTENT_READ} – strongly consistent summary reads when {@code true}</li>
 *   <li>{@code HAVING_ORDER_COUNT_THRESHOLD} – HAVING {@code orderCount} threshold</li>
 * </ul>
 */
public final class ProjectionBenchmarkRunner {

    private static final int DEFAULT_CUSTOMER_COUNT = 1000;
    private static final int DEFAULT_ORDERS_PER_CUSTOMER = 1000;
    private static final int DEFAULT_ITERATIONS = 10;
    private static final int DEFAULT_WARMUP = 3;
    private static final int JOIN_PAGINATION_PAGE_SIZE = 100;
    private static final int SUMMARY_PAGINATION_PAGE_SIZE = 10;

    private ProjectionBenchmarkRunner() {
    }

    public static void main(String[] args) throws Exception {
        int customerCount = parseIntEnv("CUSTOMER_COUNT", DEFAULT_CUSTOMER_COUNT);
        int ordersPerCustomer = parseIntEnv("ORDERS_PER_CUSTOMER", DEFAULT_ORDERS_PER_CUSTOMER);
        int iterations = parseIntEnv("BENCHMARK_ITERATIONS", DEFAULT_ITERATIONS);
        int warmup = parseIntEnv("BENCHMARK_WARMUP", DEFAULT_WARMUP);
        String outputFile = System.getenv("BENCHMARK_OUTPUT_FILE");
        String materializationOutputFile = envOrDefault(
            "BENCHMARK_MATERIALIZATION_OUTPUT_FILE",
            "/tmp/stream_projections_materialization_and_backfill_costs_1000_customers_1000_orders.csv");
        String backend = envOrDefault("BENCHMARK_BACKEND", "memory");
        boolean useDynamoDb = "dynamodb".equalsIgnoreCase(backend);
        boolean consistentRead = parseBooleanEnv("PROJECTION_CONSISTENT_READ", false);
        boolean bulkSeed = parseBooleanEnv("BENCHMARK_BULK_SEED", false);

        System.out.printf(Locale.ROOT,
                          "Building projection state (%,d customers x %,d orders, backend=%s, bulkSeed=%s)...%n",
                          customerCount, ordersPerCustomer, backend, bulkSeed);
        System.out.flush();

        long buildStart = System.nanoTime();
        ProjectionWriteMetrics.reset();
        ProjectionState state = buildState(customerCount, ordersPerCustomer, useDynamoDb, consistentRead, bulkSeed);
        long buildMs = state.buildMs > 0L ? state.buildMs : (System.nanoTime() - buildStart) / 1_000_000L;
        if (useDynamoDb && bulkSeed) {
            validateBulkSeed(state.access, customerCount, ordersPerCustomer);
        }
        if (useDynamoDb && !bulkSeed) {
            System.out.println("DynamoDB backend: skipping materialization (assumes summary tables are caught up).");
        } else if (useDynamoDb) {
            System.out.printf(Locale.ROOT,
                              "DynamoDB bulk seed complete in %,d ms.%n",
                              buildMs);
        } else {
            System.out.printf(Locale.ROOT,
                              "Build complete in %,d ms (%s). Target rows: %,d%n",
                              buildMs, bulkSeed ? "bulk fast path" : "stream simulation",
                              customerCount);
        }

        int havingThreshold = parseIntEnv("HAVING_ORDER_COUNT_THRESHOLD",
                                         Math.min(500, Math.max(0, ordersPerCustomer - 1)));
        List<Scenario> scenarios = buildScenarios(state, havingThreshold, useDynamoDb);
        validateScenarioNames(scenarios);
        List<ScenarioResult> results = new ArrayList<>();

        System.out.println();
        printHeader();
        for (Scenario scenario : scenarios) {
            ScenarioResult result = runScenario(scenario, state.access, warmup, iterations);
            results.add(result);
            printRow(result);
        }
        printFooter();

        System.out.println();
        System.out.printf(Locale.ROOT,
                          "Write-path note: materializing %,d orders took %,d ms "
                          + "(%.2f us/order). Reads below assume projection is already caught up.%n",
                          (long) customerCount * ordersPerCustomer,
                          buildMs,
                          (buildMs * 1000.0) / Math.max(1L, (long) customerCount * ordersPerCustomer));

        if (outputFile != null && !outputFile.isEmpty()) {
            String region = envOrDefault("AWS_REGION", Region.EU_WEST_1.id());
            writeCsv(outputFile, results, region, iterations, warmup, customerCount, ordersPerCustomer, consistentRead);
            System.out.println("Wrote CSV to " + outputFile);
        }
        writeMaterializationCsv(materializationOutputFile, buildMs,
                                envOrDefault("AWS_REGION", Region.EU_WEST_1.id()),
                                customerCount, ordersPerCustomer, warmup, iterations, bulkSeed);
    }

    private static ProjectionState buildState(int customerCount,
                                              int ordersPerCustomer,
                                              boolean useDynamoDb,
                                              boolean consistentRead,
                                              boolean bulkSeed) {
        ProjectionSpec byCustomer = ProjectionDynamoDbBenchmarkAccess.BY_CUSTOMER_SPEC;

        ProjectionSpec byCustomerRegion = Projections.builder("OrdersByCustomerRegion")
                                                     .sourceEntityType("Order")
                                                     .groupBy("customerId", "region")
                                                     .target(TargetTable.of("OrdersByCustomerRegion",
                                                                            "customerId", "region"))
                                                     .field("orderCount", AggregateDefinition.count())
                                                     .field("totalAmount", AggregateDefinition.sum("amount"))
                                                     .build();

        ProjectionSpec global = Projections.builder("OrdersGlobal")
                                           .sourceEntityType("Order")
                                           .groupBy()
                                           .target(TargetTable.of("OrdersGlobal", "pk"))
                                           .field("totalOrders", AggregateDefinition.count())
                                           .field("totalRevenue", AggregateDefinition.sum("amount"))
                                           .field("minAmount", AggregateDefinition.min("amount"))
                                           .field("maxAmount", AggregateDefinition.max("amount"))
                                           .build();

        ProjectionHarness harnessByCustomer = ProjectionHarness.of(byCustomer);
        ProjectionHarness harnessByCustomerRegion = ProjectionHarness.of(byCustomerRegion);
        ProjectionHarness harnessGlobal = ProjectionHarness.of(global);

        Map<JoinType, JoinProjectionHarness> joinHarnesses = new EnumMap<>(JoinType.class);
        for (JoinType joinType : JoinType.values()) {
            joinHarnesses.put(joinType, JoinProjectionHarness.of(customersOrdersJoinSpec(joinType)));
        }

        Map<String, String> customerRegion = new LinkedHashMap<>();
        long buildMs = 0L;
        int parallelism = parseIntEnv("BUILD_PARALLELISM", Runtime.getRuntime().availableProcessors());
        boolean seedProgress = parseBooleanEnv("BENCHMARK_SEED_PROGRESS", true);
        if (!useDynamoDb) {
            BenchmarkBulkSeeder.SeedContext seedContext = BenchmarkBulkSeeder.newContext(
                customerCount, ordersPerCustomer, parallelism, seedProgress, bulkSeed,
                harnessByCustomer, harnessByCustomerRegion, harnessGlobal, joinHarnesses);
            buildMs = BenchmarkBulkSeeder.seedMainDataset(seedContext);
            BenchmarkBulkSeeder.seedExtensions(seedContext);
            customerRegion.putAll(seedContext.customerRegion);
        } else if (bulkSeed) {
            String region = envOrDefault("AWS_REGION", Region.EU_WEST_1.id());
            String summaryTable = envOrDefault("PROJECTION_SUMMARY_TABLE", "OrdersByCustomer");
            String summaryRegionTable = envOrDefault("PROJECTION_SUMMARY_REGION_TABLE", "OrdersByCustomerRegion");
            String globalTable = envOrDefault("PROJECTION_GLOBAL_TABLE", "OrdersGlobal");
            DynamoDbClient client = DynamoDbClient.builder().region(Region.of(region)).build();
            BenchmarkBulkSeeder.DynamoSeedContext dynamoCtx = new BenchmarkBulkSeeder.DynamoSeedContext();
            dynamoCtx.client = client;
            dynamoCtx.customerCount = customerCount;
            dynamoCtx.ordersPerCustomer = ordersPerCustomer;
            dynamoCtx.parallelism = parallelism;
            dynamoCtx.summaryTable = summaryTable;
            dynamoCtx.regionTable = summaryRegionTable;
            dynamoCtx.globalTable = globalTable;
            dynamoCtx.byCustomerSpec = byCustomer;
            dynamoCtx.regionSpec = byCustomerRegion;
            dynamoCtx.globalSpec = global;
            dynamoCtx.writeParallelism = parseIntEnv("BENCHMARK_DDB_WRITE_PARALLELISM", 8);
            dynamoCtx.writePauseMs = parseLongEnv("BENCHMARK_DDB_WRITE_PAUSE_MS", 0L);
            dynamoCtx.logProgress = seedProgress;
            for (JoinType joinType : JoinType.values()) {
                dynamoCtx.joinTables.put(joinType,
                    envOrDefault("PROJECTION_JOIN_TABLE_" + joinType.name(),
                                 "CustomersOrdersJoin" + joinType.name().charAt(0)
                                 + joinType.name().substring(1).toLowerCase(Locale.ROOT)));
            }
            buildMs = BenchmarkBulkSeedFastPath.seedDynamoDb(dynamoCtx);
            customerRegion.putAll(dynamoCtx.customerRegion);
        } else {
            for (int c = 1; c <= customerCount; c++) {
                String customerId = "c" + c;
                customerRegion.put(customerId, (c % 2 == 0) ? "EU" : "US");
            }
            customerRegion.put("c1", "APAC");
        }

        ProjectionDynamoDbBenchmarkAccess access;
        if (useDynamoDb) {
            String region = envOrDefault("AWS_REGION", Region.EU_WEST_1.id());
            String summaryTable = envOrDefault("PROJECTION_SUMMARY_TABLE", "OrdersByCustomer");
            String summaryRegionTable = envOrDefault("PROJECTION_SUMMARY_REGION_TABLE", "OrdersByCustomerRegion");
            DynamoDbClient client = DynamoDbClient.builder().region(Region.of(region)).build();
            Map<JoinType, String> joinTables = new EnumMap<>(JoinType.class);
            Map<JoinType, JoinProjectionSpec> joinSpecs = new EnumMap<>(JoinType.class);
            for (JoinType joinType : JoinType.values()) {
                joinSpecs.put(joinType, customersOrdersJoinSpec(joinType));
                if (bulkSeed) {
                    joinTables.put(joinType,
                        envOrDefault("PROJECTION_JOIN_TABLE_" + joinType.name(),
                                     "CustomersOrdersJoin" + joinType.name().charAt(0)
                                     + joinType.name().substring(1).toLowerCase(Locale.ROOT)));
                }
            }
            access = ProjectionDynamoDbBenchmarkAccess.forDynamoDb(client, summaryTable, summaryRegionTable,
                                                                   joinTables, joinSpecs, consistentRead);
        } else {
            access = ProjectionDynamoDbBenchmarkAccess.forHarness(harnessByCustomer, harnessByCustomerRegion,
                                                                    joinHarnesses);
        }

        ProjectionState state = new ProjectionState();
        state.access = access;
        state.harnessByCustomer = harnessByCustomer;
        state.harnessByCustomerRegion = harnessByCustomerRegion;
        state.harnessGlobal = harnessGlobal;
        state.joinHarnesses = joinHarnesses;
        state.customerRegion = customerRegion;
        state.customerCount = customerCount;
        state.ordersPerCustomer = ordersPerCustomer;
        state.useDynamoDb = useDynamoDb;
        state.buildMs = buildMs;
        return state;
    }

    private static void validateBulkSeed(ProjectionDynamoDbBenchmarkAccess access,
                                         int customerCount,
                                         int ordersPerCustomer) {
        long expectedWrittenRows = 2L * customerCount + 1L + 4L * customerCount * ordersPerCustomer + 4L;
        if (ProjectionWriteMetrics.unprocessedWriteCount() != 0L) {
            throw new IllegalStateException("Projection backfill retained "
                                            + ProjectionWriteMetrics.unprocessedWriteCount() + " unprocessed writes");
        }
        if (ProjectionWriteMetrics.successfulWriteCount() != expectedWrittenRows) {
            throw new IllegalStateException("Projection backfill wrote " + ProjectionWriteMetrics.successfulWriteCount()
                                            + " rows, expected " + expectedWrittenRows);
        }
        if (access.getAggregatesByCustomer("c1").isEmpty()) {
            throw new IllegalStateException("Projection backfill did not create the c1 summary row");
        }
        for (JoinType type : JoinType.values()) {
            int observed = access.joinRowCount("c1", type);
            if (observed != ordersPerCustomer) {
                throw new IllegalStateException("Projection backfill returned " + observed + " " + type
                                                + " join rows for c1, expected " + ordersPerCustomer);
            }
        }
        requireJoinRows(access, BenchmarkBulkSeeder.ORPHAN_CUSTOMER_ID, JoinType.LEFT, 1);
        requireJoinRows(access, BenchmarkBulkSeeder.ORPHAN_CUSTOMER_ID, JoinType.FULL, 1);
        if (countJoinRowsWithOrderId(access, BenchmarkBulkSeeder.ORPHAN_ORDER_CUSTOMER_ID,
                                     JoinType.RIGHT, BenchmarkBulkSeeder.ORPHAN_ORDER_ID) != 1
            || countJoinRowsWithOrderId(access, BenchmarkBulkSeeder.ORPHAN_ORDER_CUSTOMER_ID,
                                        JoinType.FULL, BenchmarkBulkSeeder.ORPHAN_ORDER_ID) != 1) {
            throw new IllegalStateException("Projection backfill did not create both orphan-order join rows");
        }
        if (countJoinRowsWithRegion(access, "c1", JoinType.INNER, "APAC") != ordersPerCustomer) {
            throw new IllegalStateException("Projection backfill did not materialize the c1 APAC fan-out update");
        }
    }

    private static void requireJoinRows(ProjectionDynamoDbBenchmarkAccess access,
                                        String customerId,
                                        JoinType type,
                                        int expectedRows) {
        int observed = access.joinRowCount(customerId, type);
        if (observed != expectedRows) {
            throw new IllegalStateException("Projection backfill returned " + observed + " " + type
                                            + " join rows for " + customerId + ", expected " + expectedRows);
        }
    }

    private static JoinProjectionSpec customersOrdersJoinSpec(JoinType joinType) {
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

    private static List<Scenario> buildScenarios(ProjectionState state,
                                                 int havingThreshold,
                                                 boolean useDynamoDb) {
        ProjectionDynamoDbBenchmarkAccess access = state.access;
        // Orders for c1 were seeded with US (odd index); customer MODIFY to APAC does not rewrite order region.
        String c1OrderRegion = "US";
        int joinRowLimit = state.ordersPerCustomer + 100;

        List<Scenario> scenarios = new ArrayList<>();

        scenarios.add(new Scenario(
            "single_customer_by_key",
            "Reads the precomputed summary row for one customer using GetItem.",
            "GetItem(summary)",
            () -> access.getAggregatesByCustomer("c1").isEmpty() ? 0 : 1));

        scenarios.add(new Scenario(
            "scan_100_customers",
            "Reads the first 100 customer summary rows using a summary-table scan.",
            "Scan(summary)",
            () -> access.listSummaryRowCount(100)));

        scenarios.add(new Scenario(
            "count_orders_one_customer",
            "Returns the precomputed order count for customer c1.",
            "GetItem(summary)",
            () -> access.getAggregatesByCustomer("c1").get("orderCount") == null ? 0 : 1));

        scenarios.add(new Scenario(
            "sum_amount_one_customer",
            "Returns the precomputed order amount total for customer c1.",
            "GetItem(summary)",
            () -> access.getAggregatesByCustomer("c1").get("totalAmount") == null ? 0 : 1));

        scenarios.add(new Scenario(
            "avg_amount_one_customer",
            "Returns the precomputed average order amount for customer c1.",
            "GetItem(summary)",
            () -> access.storedAvgByCustomer("c1", "avgAmount") == null ? 0 : 1));

        scenarios.add(new Scenario(
            "min_amount_one_customer",
            "Returns the precomputed minimum order amount for customer c1.",
            "GetItem(summary)",
            () -> access.getAggregatesByCustomer("c1").get("minAmount") == null ? 0 : 1));

        scenarios.add(new Scenario(
            "max_amount_one_customer",
            "Returns the precomputed maximum order amount for customer c1.",
            "GetItem(summary)",
            () -> access.getAggregatesByCustomer("c1").get("maxAmount") == null ? 0 : 1));

        scenarios.add(new Scenario(
            "all_five_functions_one_customer",
            "Returns COUNT, SUM, AVG, MIN, and MAX for customer c1 in one summary read.",
            "GetItem(summary)",
            () -> {
                Map<String, Number> aggs = access.getAggregatesByCustomer("c1");
                boolean ok = aggs.containsKey("orderCount")
                             && aggs.containsKey("totalAmount")
                             && aggs.containsKey("avgAmount")
                             && aggs.containsKey("minAmount")
                             && aggs.containsKey("maxAmount");
                return ok ? 1 : 0;
            }));

        scenarios.add(new Scenario(
            "count_and_sum_with_amount_filter",
            "Returns filtered COUNT and SUM for orders whose amount is at least 50.",
            "GetItem(summary)",
            () -> {
                Map<String, Number> aggs = access.getAggregatesByCustomer("c1");
                return aggs.containsKey("largeOrders") && aggs.containsKey("largeRevenue") ? 1 : 0;
            }));

        scenarios.add(new Scenario(
            "count_per_customer_having_gt500",
            "Returns customers whose precomputed order count is greater than " + havingThreshold + ".",
            "Scan(summary)+SummaryQuery",
            () -> {
                SummaryPage page = access.queryByCustomer(
                    SummaryQuery.builder()
                                .having(Condition.gt("orderCount", havingThreshold))
                                .limit(20)
                                .build());
                return page.rows().size();
            }));

        scenarios.add(new Scenario(
            "count_and_sum_grouped_by_two_fields",
            "Returns COUNT and SUM grouped by customerId and region for customer c1.",
            "GetItem(summary)",
            () -> access.getAggregatesByCustomerRegion("c1", c1OrderRegion).isEmpty() ? 0 : 1));

        scenarios.add(new Scenario(
            "top10_customers_by_order_count",
            "Returns the ten customers with the highest precomputed order count.",
            "Scan(summary)+SummaryQuery",
            () -> access.queryByCustomer(
                SummaryQuery.builder().orderByAggregate("orderCount", SortDirection.DESC).limit(10).build()).rows().size()));

        scenarios.add(new Scenario(
            "global_sum_and_count_no_groupby",
            "Returns COUNT and SUM for customer c1 as one summary row.",
            "GetItem(summary)",
            () -> {
                Map<String, Number> aggs = access.getAggregatesByCustomer("c1");
                return aggs.containsKey("orderCount") && aggs.containsKey("totalAmount") ? 1 : 0;
            }));

        scenarios.add(new Scenario(
            "scan_count_all_customers",
            "Reads the first 20 customer summaries containing precomputed counts.",
            "Scan(summary)",
            () -> access.listSummaryRowCount(20)));

        scenarios.add(new Scenario(
            "scan_sum_only_eu_customers",
            "Returns up to 500 customer summaries for customers in the EU region.",
            "Scan(summary)+SummaryQuery",
            () -> {
                SummaryPage page = access.queryByCustomer(
                    SummaryQuery.builder()
                                .having(Condition.eq("region", "EU"))
                                .limit(500)
                                .build());
                return page.rows().size();
            }));

        scenarios.add(new Scenario(
            "scan_having_orderby_full_combo",
            "Returns the ten customers whose count exceeds " + havingThreshold + ", ordered by total amount.",
            "Scan(summary)+SummaryQuery",
            () -> {
                SummaryPage page = access.queryByCustomer(
                    SummaryQuery.builder()
                                .having(Condition.gt("orderCount", havingThreshold))
                                .orderByAggregate("totalAmount", SortDirection.DESC)
                                .limit(10)
                                .build());
                return page.rows().size();
            }));

        addJoinScenarios(scenarios, access, joinRowLimit);

        scenarios.add(new Scenario(
            "filtered_aggregate_large_orders_one_customer",
            "Dedicated filtered COUNT+SUM for orders with amount >= 50 on c1.",
            "GetItem(summary)",
            () -> {
                Map<String, Number> aggs = access.getAggregatesByCustomer("c1");
                return aggs.containsKey("largeOrders") && aggs.containsKey("largeRevenue") ? 1 : 0;
            }));

        scenarios.add(new Scenario(
            "summary_pagination_having_page2",
            "Page 2 (offset " + SUMMARY_PAGINATION_PAGE_SIZE + ") after scan+HAVING+ORDER BY aggregate sort.",
            "Scan(summary)+SummaryQuery page 2",
            () -> runSummaryPaginationPage2(access, havingThreshold)));

        scenarios.add(new Scenario(
            "join_pagination_page2",
            "Page 2 of joined orders for c1 (limit " + JOIN_PAGINATION_PAGE_SIZE + " + LEK).",
            "Query(join) page 2",
            () -> runJoinPaginationPage2(access)));

        scenarios.add(new Scenario(
            "having_with_between",
            "HAVING orderCount BETWEEN " + (havingThreshold - 1) + " AND " + (havingThreshold + 1) + ".",
            "Scan(summary)+SummaryQuery",
            () -> {
                SummaryPage page = access.queryByCustomer(
                    SummaryQuery.builder()
                                .having(Condition.between("orderCount", havingThreshold - 1, havingThreshold + 1))
                                .limit(20)
                                .build());
                return page.rows().size();
            }));

        scenarios.add(new Scenario(
            "having_with_or",
            "HAVING orderCount > " + havingThreshold + " OR orderCount < 5.",
            "Scan(summary)+SummaryQuery",
            () -> {
                SummaryPage page = access.queryByCustomer(
                    SummaryQuery.builder()
                                .having(Condition.gt("orderCount", havingThreshold)
                                              .or(Condition.lt("orderCount", 5)))
                                .limit(20)
                                .build());
                return page.rows().size();
            }));

        scenarios.add(new Scenario(
            "outer_join_orphan_customer_left",
            "LEFT join on orphan customer " + BenchmarkBulkSeeder.ORPHAN_CUSTOMER_ID + " (parent-only row).",
            "Query(join)",
            () -> access.joinRowCount(BenchmarkBulkSeeder.ORPHAN_CUSTOMER_ID, JoinType.LEFT)));

        scenarios.add(new Scenario(
            "outer_join_orphan_order_right",
            "RIGHT join surfacing orphan order " + BenchmarkBulkSeeder.ORPHAN_ORDER_ID + " (no matching customer).",
            "Query(join)",
            () -> countJoinRowsWithOrderId(access, BenchmarkBulkSeeder.ORPHAN_ORDER_CUSTOMER_ID,
                                           JoinType.RIGHT, BenchmarkBulkSeeder.ORPHAN_ORDER_ID)));

        scenarios.add(new Scenario(
            "batch_get_five_customer_summaries",
            "Five key-scoped summary reads for c1..c5 (logical batch read).",
            "BatchGetItem(summary)",
            () -> access.batchGetSummaries("c1", "c2", "c3", "c4", "c5").size()));

        scenarios.add(new Scenario(
            "consistent_read_summary_one_customer",
            "Strongly consistent GetItem for customer c1 summary row.",
            "GetItem(consistentRead=true)",
            () -> access.consistentReadSummaryRowCount("c1")));

        scenarios.add(new Scenario(
            "top10_by_total_amount_gsi",
            "COUNT+SUM per customer, ORDER BY totalAmount DESC, top 10.",
            useDynamoDb ? "Query(GSI)+SummaryQuery" : "Scan(summary)+SummaryQuery",
            () -> access.queryByAggregateGsi(
                SummaryQuery.builder().limit(10).build(),
                "totalAmount", SortDirection.DESC).rows().size()));

        scenarios.add(new Scenario(
            "customer_modify_fanout_region",
            "INNER join c1 after parent MODIFY (region=APAC from seed extension).",
            "Query(join)",
            () -> countJoinRowsWithRegion(access, "c1", JoinType.INNER, "APAC")));

        return scenarios;
    }

    private static void addJoinScenarios(List<Scenario> scenarios,
                                           ProjectionDynamoDbBenchmarkAccess access,
                                           int joinRowLimit) {
        addJoinPair(scenarios, access, JoinType.INNER, "inner", joinRowLimit);
        addJoinPair(scenarios, access, JoinType.LEFT, "left", joinRowLimit);
        addJoinPair(scenarios, access, JoinType.RIGHT, "right", joinRowLimit);
        addJoinPair(scenarios, access, JoinType.FULL, "full", joinRowLimit);
    }

    private static void addJoinPair(List<Scenario> scenarios,
                                    ProjectionDynamoDbBenchmarkAccess access,
                                    JoinType joinType,
                                    String suffix,
                                    int joinRowLimit) {
        scenarios.add(new Scenario(
            "join_all_orders_one_customer_" + suffix,
            joinType + " join customer c1 with all orders (raw join, no aggregation).",
            "Query(join)",
            () -> {
                JoinProjectionHarness.JoinPage page =
                    access.joinPage("c1", joinRowLimit, null, joinType);
                return page.rows().size();
            }));

        scenarios.add(new Scenario(
            "join_then_count_and_sum_" + suffix,
            joinType + " join c1 + COUNT + SUM collapsed to precomputed summary for c1.",
            "GetItem(summary)",
            () -> {
                Map<String, Number> aggs = access.getAggregatesByCustomer("c1");
                return (aggs.containsKey("orderCount") && aggs.containsKey("totalAmount")) ? 1 : 0;
            }));
    }

    private static int runSummaryPaginationPage2(ProjectionDynamoDbBenchmarkAccess access, int havingThreshold) {
        SummaryQuery base = SummaryQuery.builder()
                                        .having(Condition.gt("orderCount", havingThreshold))
                                        .orderByAggregate("totalAmount", SortDirection.DESC)
                                        .limit(SUMMARY_PAGINATION_PAGE_SIZE)
                                        .build();
        SummaryPage page1 = access.queryByCustomer(base);
        if (page1.cursor() == null) {
            return 0;
        }
        SummaryPage page2 = access.queryByCustomer(
            SummaryQuery.builder()
                        .having(Condition.gt("orderCount", havingThreshold))
                        .orderByAggregate("totalAmount", SortDirection.DESC)
                        .limit(SUMMARY_PAGINATION_PAGE_SIZE)
                        .cursor(page1.cursor())
                        .build());
        return page2.rows().size();
    }

    private static int runJoinPaginationPage2(ProjectionDynamoDbBenchmarkAccess access) {
        JoinProjectionHarness.JoinPage page1 =
            access.joinPage("c1", JOIN_PAGINATION_PAGE_SIZE, null, JoinType.INNER);
        if (page1.cursor() == null) {
            return 0;
        }
        JoinProjectionHarness.JoinPage page2 =
            access.joinPage("c1", JOIN_PAGINATION_PAGE_SIZE, page1.cursor(), JoinType.INNER);
        return page2.rows().size();
    }

    private static int countJoinRowsWithOrderId(ProjectionDynamoDbBenchmarkAccess access,
                                                String customerId,
                                                JoinType joinType,
                                                String orderId) {
        int count = 0;
        for (Map<String, AttributeValue> row : access.joinRows(customerId, joinType)) {
            AttributeValue sk = row.get("orderId");
            if (sk != null && orderId.equals(sk.s())) {
                count++;
            }
        }
        return count;
    }

    private static int countJoinRowsWithRegion(ProjectionDynamoDbBenchmarkAccess access,
                                               String customerId,
                                               JoinType joinType,
                                               String region) {
        int count = 0;
        for (Map<String, AttributeValue> row : access.joinRows(customerId, joinType)) {
            AttributeValue regionAttr = row.get("region");
            if (regionAttr != null && region.equals(regionAttr.s())) {
                count++;
            }
        }
        return count;
    }

    private static void validateScenarioNames(List<Scenario> scenarios) {
        List<String> expected = BenchmarkScenarioCatalog.scenarioKeys();
        List<String> actual = scenarios.stream().map(s -> s.name).collect(Collectors.toList());
        if (!actual.equals(expected)) {
            throw new IllegalStateException("Scenario keys mismatch. Expected " + expected + " but got " + actual);
        }
    }

    private static ScenarioResult runScenario(Scenario scenario,
                                              ProjectionDynamoDbBenchmarkAccess access,
                                              int warmup,
                                              int iterations) {
        int expectedRows = scenario.action.get();
        if (expectedRows == 0 && !scenario.allowsEmptyResult) {
            throw new IllegalStateException("Scenario " + scenario.name
                                            + " returned no rows during preflight validation");
        }
        for (int i = 0; i < warmup; i++) {
            scenario.action.get();
        }
        long[] samplesNs = new long[iterations];
        int rows = 0;
        double totalReadCapacityUnits = 0.0d;
        long totalRequestCount = 0L;
        for (int i = 0; i < iterations; i++) {
            access.resetBenchmarkMetrics();
            long start = System.nanoTime();
            rows = scenario.action.get();
            samplesNs[i] = System.nanoTime() - start;
            if (rows != expectedRows) {
                throw new IllegalStateException("Scenario " + scenario.name + " returned " + rows
                                                + " rows, expected " + expectedRows);
            }
            totalReadCapacityUnits += access.benchmarkReadCapacityUnits() == null
                                      ? 0.0d : access.benchmarkReadCapacityUnits();
            totalRequestCount += access.benchmarkRequestCount() == null
                                 ? 0L : access.benchmarkRequestCount();
        }
        Arrays.sort(samplesNs);
        double avgMs = Arrays.stream(samplesNs).average().orElse(0) / 1_000_000.0;
        long p50Ms = samplesNs[Math.min(samplesNs.length - 1,
                                        (int) Math.floor(0.50 * (samplesNs.length - 1)))] / 1_000_000L;
        long p95Ms = samplesNs[Math.min(samplesNs.length - 1,
                                        (int) Math.floor(0.95 * (samplesNs.length - 1)))] / 1_000_000L;
        double avgUs = Arrays.stream(samplesNs).average().orElse(0) / 1_000.0;
        return new ScenarioResult(scenario.name, scenario.description, scenario.ddbOp,
                                  avgMs, p50Ms, p95Ms, expectedRows, rows, avgUs,
                                  totalReadCapacityUnits / iterations, totalRequestCount / iterations,
                                  totalReadCapacityUnits, totalRequestCount);
    }

    private static final String READ_CSV_HEADER =
        "Run ID,Solution,Scenario ID,Scenario,Category,Description,Execution Path,Result Status,Expected Rows,Observed Rows,"
        + "Average Latency (ms),P50 Latency (ms),P95 Latency (ms),Average Read Capacity Units,"
        + "Average Write Capacity Units,Average DynamoDB Requests,Total Read Capacity Units,"
        + "Total Write Capacity Units,Total DynamoDB Requests,AWS Region,EC2 Instance Type,DynamoDB Billing Mode,"
        + "Read Consistency,Customer Count,Orders Per Customer,Warmup Iterations,Measured Iterations";

    private static void writeCsv(String path,
                                 List<ScenarioResult> results,
                                 String region,
                                 int iterations,
                                 int warmup,
                                 int customerCount,
                                 int ordersPerCustomer,
                                 boolean defaultConsistentRead) throws IOException {
        Path file = Paths.get(path);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(
            file, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            out.println(READ_CSV_HEADER);
            for (ScenarioResult r : results) {
                BenchmarkScenarioCatalog.ScenarioMetadata metadata = BenchmarkScenarioCatalog.metadata(r.name);
                String consistency = "consistent_read_summary_one_customer".equals(r.name) || defaultConsistentRead
                                     ? "Strong" : "Eventual";
                out.printf(Locale.US,
                           "%s,%s,%s,%s,%s,%s,%s,PASS,%d,%d,%.2f,%.2f,%.2f,%.2f,0.00,%d,%.2f,0.00,%d,%s,%s,%s,%s,%d,%d,%d,%d%n",
                           csv(envOrDefault("BENCHMARK_RUN_ID", "not-configured")),
                           "Stream Projections",
                           csv(r.name),
                           csv(metadata.name()),
                           csv(metadata.category()),
                           csv(metadata.workload()),
                           csv(metadata.streamProjectionsPath()),
                           r.expectedRows,
                           r.rows,
                           r.avgMs,
                           (double) r.p50Ms,
                           (double) r.p95Ms,
                           r.readCapacityUnits,
                           r.requestCount,
                           r.totalReadCapacityUnits,
                           r.totalRequestCount,
                           csv(region),
                           csv(envOrDefault("INSTANCE_TYPE", "not-configured")),
                           csv(envOrDefault("DYNAMODB_BILLING_MODE", "not-configured")),
                           consistency,
                           customerCount,
                           ordersPerCustomer,
                           warmup,
                           iterations);
            }
        }
    }

    private static void writeMaterializationCsv(String path,
                                                long buildMs,
                                                String region,
                                                int customerCount,
                                                int ordersPerCustomer,
                                                int warmup,
                                                int iterations,
                                                boolean bulkSeed) throws IOException {
        Path file = Paths.get(path);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        long expectedRows = 2L * customerCount + 1L + 4L * customerCount * ordersPerCustomer + 4L;
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(
            file, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            out.println("Run ID,Phase,Projection Target,Status,Measurement Mode,Source Records,Expected Target Rows,"
                        + "Written Target Rows,Duration (ms),Target Write Capacity Units,DynamoDB Requests,Retry Attempts,"
                        + "Unprocessed Writes,Validation Read Capacity Units,Validation Requests,Materialization Lag (ms),"
                        + "AWS Region,EC2 Instance Type,DynamoDB Billing Mode,Customer Count,Orders Per Customer,"
                        + "Warmup Iterations,Measured Iterations,Failure Detail");
            out.printf(Locale.US,
                       "%s,Backfill,All projection targets,%s,%s,%d,%d,%d,%.2f,%.2f,%d,%d,%d,0.00,0,%.2f,%s,%s,%s,%d,%d,%d,%d,None%n",
                       csv(envOrDefault("BENCHMARK_RUN_ID", "not-configured")),
                       bulkSeed ? "PASS" : "SKIPPED",
                       bulkSeed ? "Full DynamoDB backfill (prepare, write, retry, validate)" : "Existing projection state",
                       (long) customerCount * ordersPerCustomer,
                       expectedRows,
                       bulkSeed ? ProjectionWriteMetrics.successfulWriteCount() : 0L,
                       (double) buildMs,
                       ProjectionWriteMetrics.writeCapacityUnits(),
                       ProjectionWriteMetrics.requestCount(),
                       ProjectionWriteMetrics.retryCount(),
                       ProjectionWriteMetrics.unprocessedWriteCount(),
                       (double) buildMs,
                       csv(region),
                       csv(envOrDefault("INSTANCE_TYPE", "not-configured")),
                       csv(envOrDefault("DYNAMODB_BILLING_MODE", "not-configured")),
                       customerCount,
                       ordersPerCustomer,
                       warmup,
                       iterations);
        }
        System.out.println("Wrote materialization CSV to " + path);
    }

    private static int parseIntEnv(String name, int defaultValue) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        return Integer.parseInt(v);
    }

    private static String scenarioCategory(String scenarioId) {
        if (scenarioId.contains("join")) {
            return "Join";
        }
        if (scenarioId.contains("pagination")) {
            return "Pagination";
        }
        if (scenarioId.contains("consistent")) {
            return "Consistency";
        }
        if (scenarioId.contains("scan")) {
            return "Scan and aggregation";
        }
        if (scenarioId.contains("top10") || scenarioId.contains("having") || scenarioId.contains("grouped")) {
            return "Grouped aggregation";
        }
        if (scenarioId.contains("batch")) {
            return "Batch read";
        }
        return "Point read and aggregation";
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static boolean parseBooleanEnv(String name, boolean defaultValue) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(v);
    }

    private static long parseLongEnv(String name, long defaultValue) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        return Long.parseLong(v);
    }

    private static String envOrDefault(String name, String defaultValue) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? defaultValue : v;
    }

    private static String readableScenarioName(String scenarioId) {
        StringBuilder result = new StringBuilder();
        for (String word : scenarioId.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static void printHeader() {
        System.out.printf(Locale.ROOT, "%-45s %-26s %12s %10s %10s %10s %8s%n",
                          "SCENARIO", "DDB_OP", "AVG_MS", "AVG_US", "P50_MS", "P95_MS", "ROWS");
        System.out.println(repeat('-', 121));
    }

    private static void printRow(ScenarioResult r) {
        System.out.printf(Locale.ROOT, "%-45s %-26s %12.6f %10.1f %10d %10d %8d%n",
                          r.name, r.ddbOp, r.avgMs, r.avgUs, r.p50Ms, r.p95Ms, r.rows);
    }

    private static void printFooter() {
        System.out.println(repeat('-', 121));
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static final class ProjectionState {
        private ProjectionDynamoDbBenchmarkAccess access;
        private ProjectionHarness harnessByCustomer;
        private ProjectionHarness harnessByCustomerRegion;
        private ProjectionHarness harnessGlobal;
        private Map<JoinType, JoinProjectionHarness> joinHarnesses;
        private boolean useDynamoDb;
        private Map<String, String> customerRegion;
        private int customerCount;
        private int ordersPerCustomer;
        private long buildMs;
    }

    private static final class Scenario {
        private final String name;
        private final String description;
        private final String ddbOp;
        private final Supplier<Integer> action;
        private final boolean allowsEmptyResult;

        private Scenario(String name, String description, String ddbOp, Supplier<Integer> action) {
            this(name, description, ddbOp, action, "having_with_between".equals(name));
        }

        private Scenario(String name,
                         String description,
                         String ddbOp,
                         Supplier<Integer> action,
                         boolean allowsEmptyResult) {
            this.name = name;
            this.description = description;
            this.ddbOp = ddbOp;
            this.action = action;
            this.allowsEmptyResult = allowsEmptyResult;
        }
    }

    private static final class ScenarioResult {
        private final String name;
        private final String description;
        private final String ddbOp;
        private final double avgMs;
        private final long p50Ms;
        private final long p95Ms;
        private final int expectedRows;
        private final int rows;
        private final double avgUs;
        private final Double readCapacityUnits;
        private final Long requestCount;
        private final double totalReadCapacityUnits;
        private final long totalRequestCount;

        private ScenarioResult(String name, String description, String ddbOp,
                               double avgMs, long p50Ms, long p95Ms, int expectedRows, int rows, double avgUs,
                               Double readCapacityUnits, Long requestCount,
                               double totalReadCapacityUnits, long totalRequestCount) {
            this.name = name;
            this.description = description;
            this.ddbOp = ddbOp;
            this.avgMs = avgMs;
            this.p50Ms = p50Ms;
            this.p95Ms = p95Ms;
            this.expectedRows = expectedRows;
            this.rows = rows;
            this.avgUs = avgUs;
            this.readCapacityUnits = readCapacityUnits;
            this.requestCount = requestCount;
            this.totalReadCapacityUnits = totalReadCapacityUnits;
            this.totalRequestCount = totalRequestCount;
        }
    }
}
