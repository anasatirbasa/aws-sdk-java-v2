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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.utils.Validate;

/**
 * Reads projected summary rows from DynamoDB.
 *
 * <ul>
 *   <li>{@link #getItem} — point read on summary partition key</li>
 *   <li>{@link #queryByAggregateGsi} — key-path Query on an aggregate GSI (allowed under
 *       {@link ProjectionExecutionMode#STRICT_KEY_ONLY})</li>
 *   <li>{@link #scanPage} / {@link #query} — require {@link ProjectionExecutionMode#ALLOW_SCAN}</li>
 * </ul>
 */
@SdkPublicApi
public final class DynamoDbSummaryTableReader {

    private static final int DEFAULT_SCAN_PAGE_SIZE = 1000;
    private static final int BATCH_GET_MAX_KEYS = 100;

    private final DynamoDbClient client;
    private final ProjectionSpec projection;
    private final String tableName;
    private final ProjectionExecutionMode executionMode;
    private final boolean consistentRead;
    private final AtomicLong benchmarkRequestCount = new AtomicLong();
    private final DoubleAdder benchmarkReadCapacityUnits = new DoubleAdder();

    private DynamoDbSummaryTableReader(Builder builder) {
        this.client = Validate.paramNotNull(builder.client, "client");
        this.projection = Validate.paramNotNull(builder.projection, "projection");
        this.tableName = Validate.paramNotBlank(builder.tableName, "tableName");
        this.executionMode = builder.executionMode == null
                             ? ProjectionExecutionMode.STRICT_KEY_ONLY
                             : builder.executionMode;
        this.consistentRead = builder.consistentRead;
    }

    public DynamoDbSummaryTableReader(DynamoDbClient client,
                                      ProjectionSpec projection,
                                      String tableName) {
        this(builder().client(client).projection(projection).tableName(tableName));
    }

    public DynamoDbSummaryTableReader(DynamoDbClient client,
                                      ProjectionSpec projection,
                                      String tableName,
                                      ProjectionExecutionMode executionMode) {
        this(builder().client(client)
                      .projection(projection)
                      .tableName(tableName)
                      .executionMode(executionMode));
    }

    public static Builder builder() {
        return new Builder();
    }

    public ProjectionExecutionMode executionMode() {
        return executionMode;
    }

    /**
     * When {@code true}, summary reads use strongly consistent reads (2× RCU). Does not remove
     * Streams pipeline lag relative to the source table.
     */
    public boolean consistentRead() {
        return consistentRead;
    }

    /**
     * Number of DynamoDB read requests issued by this reader since construction.
     * Intended for benchmark and diagnostics output.
     */
    public long benchmarkRequestCount() {
        return benchmarkRequestCount.get();
    }

    /**
     * Read capacity units returned by DynamoDB for this reader since construction.
     * Intended for benchmark and diagnostics output.
     */
    public double benchmarkReadCapacityUnits() {
        return benchmarkReadCapacityUnits.sum();
    }

    public void resetBenchmarkMetrics() {
        benchmarkRequestCount.set(0L);
        benchmarkReadCapacityUnits.reset();
    }

    /**
     * GetItem on the summary table by partition (and optional sort) key attributes.
     */
    public Optional<SummaryRow> getItem(Map<String, AttributeValue> key) {
        Validate.paramNotNull(key, "key");
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                                                                .tableName(tableName)
                                                                .key(key)
                                                                .consistentRead(consistentRead)
                                                                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                                                                .build());
        recordBenchmarkRead(response.consumedCapacity());
        if (response.item() == null || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toSummaryRow(response.item()));
    }

    /**
     * BatchGetItem for up to 100 keys per request (automatically chunked).
     * Missing keys are omitted from the result list.
     */
    public List<SummaryRow> batchGetItems(List<Map<String, AttributeValue>> keys) {
        Validate.paramNotNull(keys, "keys");
        if (keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<SummaryRow> rows = new ArrayList<>();
        for (int start = 0; start < keys.size(); start += BATCH_GET_MAX_KEYS) {
            int end = Math.min(keys.size(), start + BATCH_GET_MAX_KEYS);
            List<Map<String, AttributeValue>> chunk = keys.subList(start, end);
            Map<String, KeysAndAttributes> requestItems = new LinkedHashMap<>();
            requestItems.put(tableName, KeysAndAttributes.builder()
                                                         .keys(chunk)
                                                         .consistentRead(consistentRead)
                                                         .build());
            int attempts = 0;
            while (!requestItems.isEmpty()) {
                BatchGetItemResponse response = client.batchGetItem(
                    BatchGetItemRequest.builder()
                                       .requestItems(requestItems)
                                       .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                                       .build());
                recordBenchmarkReads(response.consumedCapacity());
                List<Map<String, AttributeValue>> items = response.responses().getOrDefault(tableName,
                                                                                             Collections.emptyList());
                for (Map<String, AttributeValue> item : items) {
                    if (item != null && !item.isEmpty()) {
                        rows.add(toSummaryRow(item));
                    }
                }
                requestItems = response.unprocessedKeys();
                if (!requestItems.isEmpty() && ++attempts >= 5) {
                    throw new ProjectionException("BatchGetItem left unprocessed keys after 5 attempts");
                }
            }
        }
        return rows;
    }

    /**
     * Query the aggregate GSI in sort-key order (ASC/DESC via {@code scanIndexForward}).
     * Allowed under {@link ProjectionExecutionMode#STRICT_KEY_ONLY}.
     */
    public SummaryPage queryByAggregateGsi(Integer limit, String cursor, boolean scanIndexForward) {
        return queryByAggregateGsi(limit, cursor, scanIndexForward, null);
    }

    /**
     * Query the aggregate GSI with optional {@link SummaryQuery} for projection/filter hints.
     */
    public SummaryPage queryByAggregateGsi(Integer limit,
                                           String cursor,
                                           boolean scanIndexForward,
                                           SummaryQuery queryHint) {
        AggregateGsi gsi = projection.aggregateGsi().orElseThrow(() ->
            new ProjectionException("projection has no aggregateGsi declared"));
        QueryRequest.Builder qb = QueryRequest.builder()
            .tableName(tableName)
            .indexName(gsi.indexName())
            .keyConditionExpression("#pk = :pk")
            .scanIndexForward(scanIndexForward)
            .consistentRead(consistentRead)
            .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
        applyGsiReadOptimizations(qb, queryHint, gsi);
        if (limit != null) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            qb.limit(limit);
        }
        if (cursor != null && !cursor.isEmpty()) {
            qb.exclusiveStartKey(ProjectionCursors.decodeExclusiveStartKey(cursor));
        }
        QueryResponse response = client.query(qb.build());
        recordBenchmarkRead(response.consumedCapacity());
        List<SummaryRow> rows = new ArrayList<>();
        for (Map<String, AttributeValue> item : response.items()) {
            rows.add(toSummaryRow(item));
        }
        String next = response.lastEvaluatedKey() == null || response.lastEvaluatedKey().isEmpty()
                      ? null
                      : ProjectionCursors.encodeExclusiveStartKey(response.lastEvaluatedKey());
        return new SummaryPage(rows, next);
    }

    /**
     * One Scan page in DynamoDB key order. Requires {@link ProjectionExecutionMode#ALLOW_SCAN}.
     */
    public SummaryPage scanPage(Integer limit, String cursor) {
        return scanPage(limit, cursor, null);
    }

    /**
     * One Scan page with optional {@link SummaryQuery} for projection/filter hints.
     */
    public SummaryPage scanPage(Integer limit, String cursor, SummaryQuery queryHint) {
        requireAllowScan("summary table Scan");
        ScanRequest.Builder req = ScanRequest.builder()
                                             .tableName(tableName)
                                             .consistentRead(consistentRead)
                                             .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
        applyScanReadOptimizations(req, queryHint);
        if (limit != null) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            req.limit(limit);
        }
        if (cursor != null && !cursor.isEmpty()) {
            req.exclusiveStartKey(ProjectionCursors.decodeExclusiveStartKey(cursor));
        }
        ScanResponse response = client.scan(req.build());
        recordBenchmarkRead(response.consumedCapacity());
        List<SummaryRow> rows = new ArrayList<>();
        for (Map<String, AttributeValue> item : response.items()) {
            rows.add(toSummaryRow(item));
        }
        String next = response.lastEvaluatedKey() == null || response.lastEvaluatedKey().isEmpty()
                      ? null
                      : ProjectionCursors.encodeExclusiveStartKey(response.lastEvaluatedKey());
        return new SummaryPage(rows, next);
    }

    /**
     * HAVING / ORDER BY over the summary table. Requires {@link ProjectionExecutionMode#ALLOW_SCAN}.
     *
     * <p>Routes to aggregate GSI when ORDER BY matches the declared GSI sort key and HAVING is
     * absent or pushdownable. Otherwise paginates Scan pages and applies {@link SummaryQueryEngine}.
     */
    public SummaryPage query(SummaryQuery query) {
        requireAllowScan("SummaryQuery over full summary table");
        Validate.paramNotNull(query, "query");

        Optional<SummaryPage> gsiPage = tryQueryViaAggregateGsi(query);
        if (gsiPage.isPresent()) {
            return gsiPage.get();
        }

        Optional<SummaryFilterPushdown.Pushdown> pushdown =
            SummaryFilterPushdown.tryPushdown(query.havingCondition());
        SummaryQuery engineQuery = pushdown.isPresent()
                                   ? SummaryQueryEngine.withoutHaving(query)
                                   : query;

        List<SummaryRow> accumulated = new ArrayList<>();
        String scanCursor = null;
        do {
            SummaryPage page = scanPage(DEFAULT_SCAN_PAGE_SIZE, scanCursor, query);
            accumulated.addAll(page.rows());
            scanCursor = page.cursor();
        } while (scanCursor != null);

        return SummaryQueryEngine.execute(accumulated, engineQuery);
    }

    public List<SummaryRow> scanAll() {
        requireAllowScan("summary table Scan");
        List<SummaryRow> all = new ArrayList<>();
        String cursor = null;
        do {
            SummaryPage page = scanPage(null, cursor);
            all.addAll(page.rows());
            cursor = page.cursor();
        } while (cursor != null);
        return all;
    }

    private Optional<SummaryPage> tryQueryViaAggregateGsi(SummaryQuery query) {
        if (query.orderBy().size() != 1 || !projection.aggregateGsi().isPresent()) {
            return Optional.empty();
        }
        SummaryOrderBy orderBy = query.orderBy().get(0);
        if (!orderBy.byAggregate()) {
            return Optional.empty();
        }
        AggregateGsi gsi = projection.aggregateGsi().get();
        if (!gsi.sortKeyAggregateAlias().equals(orderBy.name())) {
            return Optional.empty();
        }
        if (query.havingCondition() != null
            && !SummaryFilterPushdown.tryPushdown(query.havingCondition()).isPresent()) {
            return Optional.empty();
        }
        if (query.havingPredicate() != null) {
            return Optional.empty();
        }

        int offset = ProjectionCursors.decodeOffset(query.cursor());
        int limit = query.limit() == null ? Integer.MAX_VALUE : query.limit();
        int fetchCount = offset + limit;
        if (fetchCount <= 0 || fetchCount == Integer.MAX_VALUE) {
            fetchCount = DEFAULT_SCAN_PAGE_SIZE;
        }

        boolean scanForward = orderBy.direction() != SortDirection.DESC;
        List<SummaryRow> collected = new ArrayList<>();
        String gsiCursor = null;
        while (collected.size() < fetchCount) {
            int pageLimit = Math.min(DEFAULT_SCAN_PAGE_SIZE, fetchCount - collected.size());
            SummaryPage page = queryByAggregateGsi(pageLimit, gsiCursor, scanForward, query);
            collected.addAll(page.rows());
            gsiCursor = page.cursor();
            if (gsiCursor == null) {
                break;
            }
        }

        if (offset >= collected.size()) {
            return Optional.of(new SummaryPage(new ArrayList<>(), null));
        }
        int end = Math.min(collected.size(), offset + limit);
        List<SummaryRow> slice = new ArrayList<>(collected.subList(offset, end));
        String next = end < collected.size()
                      ? ProjectionCursors.encodeOffset(end)
                      : (gsiCursor != null ? ProjectionCursors.encodeOffset(end) : null);
        return Optional.of(new SummaryPage(slice, next));
    }

    private void applyGsiReadOptimizations(QueryRequest.Builder qb,
                                           SummaryQuery queryHint,
                                           AggregateGsi gsi) {
        Map<String, String> allNames = new LinkedHashMap<>();
        Map<String, AttributeValue> allValues = new LinkedHashMap<>();
        allNames.put("#pk", gsi.partitionKeyAttribute());
        allValues.put(":pk", AttributeValue.builder().s(gsi.partitionKeyValue()).build());

        Set<String> attrs = SummaryAttributeProjection.forQuery(projection, queryHint);
        String projectionExpression = SummaryAttributeProjection.buildExpression(attrs);
        if (projectionExpression != null) {
            allNames.putAll(SummaryAttributeProjection.buildNameMap(attrs));
            qb.projectionExpression(projectionExpression);
        }
        if (queryHint != null) {
            SummaryFilterPushdown.tryPushdown(queryHint.havingCondition()).ifPresent(p -> {
                qb.filterExpression(p.filterExpression());
                allNames.putAll(p.expressionAttributeNames());
                allValues.putAll(p.expressionAttributeValues());
            });
        }
        qb.expressionAttributeNames(allNames);
        qb.expressionAttributeValues(allValues);
    }

    private void applyScanReadOptimizations(ScanRequest.Builder req, SummaryQuery queryHint) {
        Map<String, String> allNames = new LinkedHashMap<>();
        Map<String, AttributeValue> allValues = new LinkedHashMap<>();

        Set<String> attrs = SummaryAttributeProjection.forQuery(projection, queryHint);
        String projectionExpression = SummaryAttributeProjection.buildExpression(attrs);
        if (projectionExpression != null) {
            allNames.putAll(SummaryAttributeProjection.buildNameMap(attrs));
            req.projectionExpression(projectionExpression);
        }
        if (queryHint != null) {
            SummaryFilterPushdown.tryPushdown(queryHint.havingCondition()).ifPresent(p -> {
                req.filterExpression(p.filterExpression());
                allNames.putAll(p.expressionAttributeNames());
                allValues.putAll(p.expressionAttributeValues());
            });
        }
        if (!allNames.isEmpty()) {
            req.expressionAttributeNames(allNames);
        }
        if (!allValues.isEmpty()) {
            req.expressionAttributeValues(allValues);
        }
    }

    private void requireAllowScan(String reason) {
        if (executionMode != ProjectionExecutionMode.ALLOW_SCAN) {
            throw new ProjectionExecutionPolicyException(
                "ProjectionExecutionMode.STRICT_KEY_ONLY forbids Scan: " + reason
                + ". Use ProjectionExecutionMode.ALLOW_SCAN to opt in.");
        }
    }

    private void recordBenchmarkRead(software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity capacity) {
        benchmarkRequestCount.incrementAndGet();
        if (capacity != null && capacity.capacityUnits() != null) {
            benchmarkReadCapacityUnits.add(capacity.capacityUnits());
        }
    }

    private void recordBenchmarkReads(List<software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity> capacities) {
        benchmarkRequestCount.incrementAndGet();
        if (capacities != null) {
            capacities.forEach(capacity -> {
                if (capacity != null && capacity.capacityUnits() != null) {
                    benchmarkReadCapacityUnits.add(capacity.capacityUnits());
                }
            });
        }
    }

    private SummaryRow toSummaryRow(Map<String, AttributeValue> item) {
        Map<String, Object> plain = AttributeValueMaps.fromAttributeMap(item);
        Map<String, Object> key = new LinkedHashMap<>();
        for (String attr : projection.groupBy()) {
            if (plain.containsKey(attr)) {
                key.put(attr, plain.get(attr));
            }
        }
        if (projection.groupBy().isEmpty()) {
            String pk = projection.target().partitionKey();
            if (plain.containsKey(pk)) {
                key.put(pk, plain.get(pk));
            }
            String sk = projection.target().sortKey();
            if (sk != null && plain.containsKey(sk)) {
                key.put(sk, plain.get(sk));
            }
        }
        Map<String, Number> aggs = new LinkedHashMap<>();
        for (String alias : projection.fields().keySet()) {
            Object v = plain.get(alias);
            if (v instanceof Number) {
                aggs.put(alias, (Number) v);
            } else if (v != null) {
                try {
                    aggs.put(alias, new BigDecimal(v.toString()));
                } catch (NumberFormatException ignored) {
                    // skip non-numeric aggregate slots
                }
            }
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (String attr : projection.carryForwardAttributes()) {
            if (plain.containsKey(attr) && !key.containsKey(attr) && !aggs.containsKey(attr)) {
                attributes.put(attr, plain.get(attr));
            }
        }
        return new SummaryRow(key, aggs, attributes);
    }

    public static final class Builder {
        private DynamoDbClient client;
        private ProjectionSpec projection;
        private String tableName;
        private ProjectionExecutionMode executionMode;
        private boolean consistentRead;

        public Builder client(DynamoDbClient client) {
            this.client = client;
            return this;
        }

        public Builder projection(ProjectionSpec projection) {
            this.projection = projection;
            return this;
        }

        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public Builder executionMode(ProjectionExecutionMode executionMode) {
            this.executionMode = executionMode;
            return this;
        }

        public Builder consistentRead(boolean consistentRead) {
            this.consistentRead = consistentRead;
            return this;
        }

        public DynamoDbSummaryTableReader build() {
            return new DynamoDbSummaryTableReader(this);
        }
    }
}
