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
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.enhanced.dynamodb.projection.AggregateDefinition;
import software.amazon.awssdk.enhanced.dynamodb.projection.AggregateGsi;
import software.amazon.awssdk.enhanced.dynamodb.projection.DynamoDbSummaryTableReader;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinMaterializedViewReader;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionSpec;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinType;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionExecutionMode;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionSpec;
import software.amazon.awssdk.enhanced.dynamodb.projection.Projections;
import software.amazon.awssdk.enhanced.dynamodb.projection.SortDirection;
import software.amazon.awssdk.enhanced.dynamodb.projection.SummaryPage;
import software.amazon.awssdk.enhanced.dynamodb.projection.SummaryQuery;
import software.amazon.awssdk.enhanced.dynamodb.projection.SummaryRow;
import software.amazon.awssdk.enhanced.dynamodb.projection.TargetTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Read facade for {@link ProjectionBenchmarkRunner}: in-memory harness (default) or live DynamoDB summary tables.
 */
final class ProjectionDynamoDbBenchmarkAccess {

    static final ProjectionSpec BY_CUSTOMER_SPEC = Projections.builder("OrdersByCustomer")
        .sourceEntityType("Order")
        .groupBy("customerId")
        .target(TargetTable.of("OrdersByCustomer", "customerId"))
        .field("orderCount", AggregateDefinition.count())
        .field("totalAmount", AggregateDefinition.sum("amount"))
        .field("avgAmount", AggregateDefinition.avg("amount"))
        .field("minAmount", AggregateDefinition.min("amount"))
        .field("maxAmount", AggregateDefinition.max("amount"))
        .field("largeOrders",
               AggregateDefinition.count(
                   software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionPredicate.gte("amount", 50)))
        .field("largeRevenue",
               AggregateDefinition.sum(
                   "amount",
                   software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionPredicate.gte("amount", 50)))
        .carryForward("region")
        .aggregateGsi(AggregateGsi.of("byTotalAmount", "gsiPk", "ALL", "totalAmount"))
        .build();

    private final boolean dynamoDb;
    private final ProjectionHarness harnessByCustomer;
    private final ProjectionHarness harnessByCustomerRegion;
    private final Map<JoinType, JoinProjectionHarness> joinHarnesses;
    private final Map<JoinType, JoinMaterializedViewReader> joinReaders;
    private final DynamoDbSummaryTableReader readerByCustomer;
    private final DynamoDbSummaryTableReader stronglyConsistentReaderByCustomer;
    private final DynamoDbSummaryTableReader readerByCustomerRegion;
    private final boolean consistentRead;

    private ProjectionDynamoDbBenchmarkAccess(boolean dynamoDb,
                                              ProjectionHarness harnessByCustomer,
                                              ProjectionHarness harnessByCustomerRegion,
                                              Map<JoinType, JoinProjectionHarness> joinHarnesses,
                                              Map<JoinType, JoinMaterializedViewReader> joinReaders,
                                              DynamoDbSummaryTableReader readerByCustomer,
                                              DynamoDbSummaryTableReader stronglyConsistentReaderByCustomer,
                                              DynamoDbSummaryTableReader readerByCustomerRegion,
                                              boolean consistentRead) {
        this.dynamoDb = dynamoDb;
        this.harnessByCustomer = harnessByCustomer;
        this.harnessByCustomerRegion = harnessByCustomerRegion;
        this.joinHarnesses = joinHarnesses == null ? Collections.emptyMap() : joinHarnesses;
        this.joinReaders = joinReaders == null ? Collections.emptyMap() : joinReaders;
        this.readerByCustomer = readerByCustomer;
        this.stronglyConsistentReaderByCustomer = stronglyConsistentReaderByCustomer;
        this.readerByCustomerRegion = readerByCustomerRegion;
        this.consistentRead = consistentRead;
    }

    static ProjectionDynamoDbBenchmarkAccess forHarness(ProjectionHarness byCustomer,
                                                        ProjectionHarness byCustomerRegion,
                                                        Map<JoinType, JoinProjectionHarness> joinHarnesses) {
        return new ProjectionDynamoDbBenchmarkAccess(false, byCustomer, byCustomerRegion, joinHarnesses,
                                                       null, null, null, null, false);
    }

    static ProjectionDynamoDbBenchmarkAccess forDynamoDb(DynamoDbClient client,
                                                         String summaryTable,
                                                         String summaryRegionTable,
                                                         boolean consistentRead) {
        return forDynamoDb(client, summaryTable, summaryRegionTable, null, null, consistentRead);
    }

    static ProjectionDynamoDbBenchmarkAccess forDynamoDb(DynamoDbClient client,
                                                         String summaryTable,
                                                         String summaryRegionTable,
                                                         Map<JoinType, String> joinTables,
                                                         Map<JoinType, JoinProjectionSpec> joinSpecs,
                                                         boolean consistentRead) {
        DynamoDbSummaryTableReader byCustomer = DynamoDbSummaryTableReader.builder()
            .client(client)
            .projection(BY_CUSTOMER_SPEC)
            .tableName(summaryTable)
            .executionMode(ProjectionExecutionMode.ALLOW_SCAN)
            .consistentRead(consistentRead)
            .build();
        DynamoDbSummaryTableReader strongByCustomer = DynamoDbSummaryTableReader.builder()
            .client(client)
            .projection(BY_CUSTOMER_SPEC)
            .tableName(summaryTable)
            .executionMode(ProjectionExecutionMode.ALLOW_SCAN)
            .consistentRead(true)
            .build();
        ProjectionSpec regionSpec = Projections.builder("OrdersByCustomerRegion")
            .sourceEntityType("Order")
            .groupBy("customerId", "region")
            .target(TargetTable.of("OrdersByCustomerRegion", "customerId", "region"))
            .field("orderCount", AggregateDefinition.count())
            .field("totalAmount", AggregateDefinition.sum("amount"))
            .build();
        DynamoDbSummaryTableReader byRegion = DynamoDbSummaryTableReader.builder()
            .client(client)
            .projection(regionSpec)
            .tableName(summaryRegionTable)
            .executionMode(ProjectionExecutionMode.ALLOW_SCAN)
            .consistentRead(consistentRead)
            .build();
        Map<JoinType, JoinMaterializedViewReader> joinReaders = new EnumMap<>(JoinType.class);
        if (joinTables != null && joinSpecs != null) {
            for (Map.Entry<JoinType, String> entry : joinTables.entrySet()) {
                JoinProjectionSpec spec = joinSpecs.get(entry.getKey());
                String tableName = entry.getValue();
                if (spec != null && tableName != null && !tableName.isEmpty()) {
                    joinReaders.put(entry.getKey(), JoinMaterializedViewReader.builder()
                        .client(client)
                        .projection(spec)
                        .tableName(tableName)
                        .consistentRead(consistentRead)
                        .build());
                }
            }
        }
        return new ProjectionDynamoDbBenchmarkAccess(true, null, null, null, joinReaders,
                                                     byCustomer, strongByCustomer, byRegion, consistentRead);
    }

    boolean consistentRead() {
        return consistentRead;
    }

    void resetBenchmarkMetrics() {
        if (!dynamoDb) {
            return;
        }
        if (readerByCustomer != null) {
            readerByCustomer.resetBenchmarkMetrics();
        }
        if (stronglyConsistentReaderByCustomer != null) {
            stronglyConsistentReaderByCustomer.resetBenchmarkMetrics();
        }
        if (readerByCustomerRegion != null) {
            readerByCustomerRegion.resetBenchmarkMetrics();
        }
        joinReaders.values().forEach(reader -> reader.resetBenchmarkMetrics());
    }

    Double benchmarkReadCapacityUnits() {
        if (!dynamoDb) {
            return null;
        }
        double total = 0.0d;
        if (readerByCustomer != null) {
            total += readerByCustomer.benchmarkReadCapacityUnits();
        }
        if (stronglyConsistentReaderByCustomer != null) {
            total += stronglyConsistentReaderByCustomer.benchmarkReadCapacityUnits();
        }
        if (readerByCustomerRegion != null) {
            total += readerByCustomerRegion.benchmarkReadCapacityUnits();
        }
        total += joinReaders.values().stream()
                           .mapToDouble(JoinMaterializedViewReader::benchmarkReadCapacityUnits)
                           .sum();
        return total;
    }

    Long benchmarkRequestCount() {
        if (!dynamoDb) {
            return null;
        }
        long total = 0L;
        if (readerByCustomer != null) {
            total += readerByCustomer.benchmarkRequestCount();
        }
        if (stronglyConsistentReaderByCustomer != null) {
            total += stronglyConsistentReaderByCustomer.benchmarkRequestCount();
        }
        if (readerByCustomerRegion != null) {
            total += readerByCustomerRegion.benchmarkRequestCount();
        }
        total += joinReaders.values().stream()
                           .mapToLong(JoinMaterializedViewReader::benchmarkRequestCount)
                           .sum();
        return total;
    }

    Map<String, Number> getAggregatesByCustomer(String customerId) {
        Map<String, Object> key = Collections.singletonMap("customerId", customerId);
        if (dynamoDb) {
            Optional<SummaryRow> row = readerByCustomer.getItem(
                Collections.singletonMap("customerId", AttributeValue.builder().s(customerId).build()));
            return row.map(SummaryRow::aggregates).orElse(Collections.emptyMap());
        }
        return harnessByCustomer.getAggregates(key);
    }

    Double storedAvgByCustomer(String customerId, String alias) {
        Map<String, Object> key = Collections.singletonMap("customerId", customerId);
        if (dynamoDb) {
            Map<String, Number> aggs = getAggregatesByCustomer(customerId);
            return aggs.containsKey(alias) ? aggs.get(alias).doubleValue() : null;
        }
        return harnessByCustomer.storedAvg(key, alias);
    }

    Map<String, Number> getAggregatesByCustomerRegion(String customerId, String region) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("customerId", customerId);
        key.put("region", region);
        if (dynamoDb) {
            Map<String, AttributeValue> avKey = new LinkedHashMap<>();
            avKey.put("customerId", AttributeValue.builder().s(customerId).build());
            avKey.put("region", AttributeValue.builder().s(region).build());
            return readerByCustomerRegion.getItem(avKey).map(SummaryRow::aggregates).orElse(Collections.emptyMap());
        }
        return harnessByCustomerRegion.getAggregates(key);
    }

    SummaryPage queryByCustomer(SummaryQuery query) {
        if (dynamoDb) {
            return readerByCustomer.query(query);
        }
        return harnessByCustomer.query(query);
    }

    SummaryPage queryByAggregateGsi(SummaryQuery query, String aggregateAlias, SortDirection direction) {
        boolean scanForward = direction != SortDirection.DESC;
        if (dynamoDb) {
            AggregateGsi gsi = BY_CUSTOMER_SPEC.aggregateGsi().orElse(null);
            if (gsi != null && gsi.sortKeyAggregateAlias().equals(aggregateAlias)) {
                return readerByCustomer.queryByAggregateGsi(query.limit(), query.cursor(), scanForward, query);
            }
            throw new IllegalArgumentException("No aggregate GSI is configured for " + aggregateAlias);
        }
        SummaryQuery.Builder builder = SummaryQuery.builder();
        if (query.havingCondition() != null) {
            builder.having(query.havingCondition());
        }
        if (query.havingPredicate() != null) {
            builder.having(query.havingPredicate());
        }
        builder.orderByAggregate(aggregateAlias, direction);
        if (query.limit() != null) {
            builder.limit(query.limit());
        }
        if (query.cursor() != null) {
            builder.cursor(query.cursor());
        }
        return harnessByCustomer.query(builder.build());
    }

    int listSummaryRowCount(int limit) {
        if (dynamoDb) {
            return Math.min(limit, readerByCustomer.scanPage(limit, null).rows().size());
        }
        return Math.min(limit, harnessByCustomer.listSummaryRows().size());
    }

    int joinRowCount(String customerId, JoinType joinType) {
        JoinProjectionHarness harness = joinHarnesses.get(joinType);
        if (harness != null) {
            return harness.getJoinRows(customerId).size();
        }
        JoinMaterializedViewReader reader = joinReaders.get(joinType);
        if (reader != null) {
            return reader.queryPage(customerId, null, null).rows().size();
        }
        return 0;
    }

    JoinProjectionHarness.JoinPage joinPage(String customerId, int limit, String cursor, JoinType joinType) {
        JoinProjectionHarness harness = joinHarnesses.get(joinType);
        if (harness != null) {
            return harness.queryPage(customerId, limit, cursor);
        }
        JoinMaterializedViewReader reader = joinReaders.get(joinType);
        if (reader != null) {
            return reader.queryPage(customerId, limit, cursor);
        }
        return JoinProjectionHarness.JoinPage.empty();
    }

    List<Map<String, AttributeValue>> joinRows(String customerId, JoinType joinType) {
        JoinProjectionHarness harness = joinHarnesses.get(joinType);
        if (harness != null) {
            return harness.getJoinRows(customerId);
        }
        JoinMaterializedViewReader reader = joinReaders.get(joinType);
        if (reader != null) {
            return reader.queryPage(customerId, null, null).rows();
        }
        return Collections.emptyList();
    }

    List<Map<String, Number>> batchGetSummaries(String... customerIds) {
        if (customerIds == null || customerIds.length == 0) {
            return Collections.emptyList();
        }
        List<Map<String, Number>> results = new ArrayList<>(customerIds.length);
        if (dynamoDb) {
            List<Map<String, AttributeValue>> keys = new ArrayList<>(customerIds.length);
            for (String customerId : customerIds) {
                keys.add(Collections.singletonMap("customerId",
                                                  AttributeValue.builder().s(customerId).build()));
            }
            for (SummaryRow row : readerByCustomer.batchGetItems(keys)) {
                results.add(row.aggregates());
            }
            return results;
        }
        for (String customerId : customerIds) {
            results.add(getAggregatesByCustomer(customerId));
        }
        return results;
    }

    int consistentReadSummaryRowCount(String customerId) {
        if (dynamoDb) {
            return stronglyConsistentReaderByCustomer.getItem(
                Collections.singletonMap("customerId", AttributeValue.builder().s(customerId).build())).isPresent()
                   ? 1 : 0;
        }
        return getAggregatesByCustomer(customerId).isEmpty() ? 0 : 1;
    }

    static Map<JoinType, JoinProjectionHarness> emptyJoinHarnesses() {
        return new EnumMap<>(JoinType.class);
    }
}
