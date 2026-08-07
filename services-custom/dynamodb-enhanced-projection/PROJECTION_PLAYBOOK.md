# Stream Projection Playbook (PoC)

This playbook mirrors the Enhanced Queries playbook structure for **aggregations** and
**joins**, showing how the same customer questions are answered with **stream projections**.

---

## Mental model

| Enhanced Queries | Stream projections |
|---|---|
| Compute aggregations / joins **at read time** over Query/Scan results | Maintain aggregations + join rows **at write time** from DynamoDB Streams |
| No extra AWS infrastructure | Requires Streams + Lambda (or equivalent) + target tables |
| Ad-hoc AVG/MIN/MAX/joins possible | Native: `count`, `sum`, stored `avg`, `min`/`max`; INNER/LEFT/RIGHT/FULL join MV |
| HAVING / ORDER BY / pagination | Read-time over summary rows (`SummaryQuery`); native Scan LEK cursors; optional aggregate GSI Query |
| STRICT_KEY_ONLY / ALLOW_SCAN | `ProjectionExecutionMode` gates summary Scan and global MIN/MAX source Scan |

```
Source table writes (Customer + Order)
       │
       ▼
DynamoDB Streams (NEW_AND_OLD_IMAGES)
       │
       ├──► ProjectionApplicator     → summary table (count/sum/avg/min/max)
       └──► JoinProjectionApplicator → join table (one row per match + parent attrs)
       │
       ▼
App reads GetItem / Query / Scan / SummaryQuery on target tables
```

---

## 1. Declare a projection (orders by customer)

```java
ProjectionSpec ordersByCustomer = Projections.builder("OrdersByCustomer")
    .sourceEntityType("Order")
    .groupBy("customerId")
    .target(TargetTable.of("OrdersByCustomer", "customerId"))
    .field("orderCount", AggregateDefinition.count())
    .field("totalAmount", AggregateDefinition.sum("amount"))
    .field("paidOrders",
           AggregateDefinition.count(ProjectionPredicate.eq("status", "paid")))
    .field("paidRevenue",
           AggregateDefinition.sum("amount", ProjectionPredicate.eq("status", "paid")))
    .field("avgAmount", AggregateDefinition.avg("amount"))
    .field("minAmount", AggregateDefinition.min("amount"))
    .field("maxAmount", AggregateDefinition.max("amount"))
    .build();
```

Equivalent Enhanced Queries intent (key-scoped):

```java
QueryExpressionBuilder.from(customers)
    .join(orders, JoinType.INNER, "customerId", "customerId")
    .keyCondition(QueryConditional.keyEqualTo(k -> k.partitionValue("c1")))
    .groupBy("customerId")
    .aggregate(AggregationFunction.COUNT, "orderId", "orderCount")
    .aggregate(AggregationFunction.SUM, "amount", "totalAmount")
    .aggregate(AggregationFunction.AVG, "amount", "avgAmount")
    .aggregate(AggregationFunction.MIN, "amount", "minAmount")
    .aggregate(AggregationFunction.MAX, "amount", "maxAmount")
    .build();
```

---

## 2. Wire the applicator (Lambda / worker)

```java
DynamoDbClient ddb = DynamoDbClient.create();
SourceGroupScanner scanner = new DynamoDbSourceGroupScanner(ddb, "orders_large", "customerId");

ProjectionApplicator applicator = ProjectionApplicator.builder()
    .client(ddb)
    .projection(ordersByCustomer)
    .sourcePartitionKey("customerId")
    .sourceSortKey("orderId")
    .sourceGroupScanner(scanner) // required when MIN/MAX are declared
    .build();

StreamProjectionRuntime runtime = StreamProjectionRuntime.builder()
    .registerAggregate(System.getenv("ORDERS_STREAM_ARN"), applicator)
    .build();
StreamProjectionLambdaAdapter projections = new StreamProjectionLambdaAdapter(runtime);

// Inside a DynamoDB Streams Lambda handler, return this response directly.
// The event-source mapping must enable ReportBatchItemFailures.
public StreamsEventResponse handleRequest(DynamodbEvent event, Context context) {
    return projections.handle(event);
}
```

Infrastructure (not provided by this library):

1. Enable Streams on the source table (`NEW_AND_OLD_IMAGES`)
2. Create the target summary table (`customerId` HASH)
3. Create a projection-state table with `projectionGroup` (HASH) and `sourceItem` (RANGE)
4. Deploy a Lambda with an event-source mapping that enables `ReportBatchItemFailures` → call
   `StreamProjectionLambdaAdapter`
5. Grant the Lambda read access to the source stream and read/write access to the target and
   state tables; configure an on-failure destination/DLQ in IaC
6. Stamp a stable, monotonically increasing `_v` version and `entityType` on every source write.
   The decoder rejects a missing `_v`; it never fabricates one because fabricated values make an
   at-least-once retry unsafe.

The library deliberately creates none of these resources. Provision source streams, summary
tables and indexes, the state table, Lambda, IAM, mapping, and queues through CDK,
CloudFormation, Terraform, or equivalent. To populate existing source items, run a separately
controlled backfill before (or while carefully coordinating) the stream consumer.

### Populate historical items

`DynamoDbProjectionBackfill` is an explicit, bounded Scan runner; it does not guess a stream
cutover point or silently create resources. Each source item must already contain a stable `_v`.

```java
DynamoDbProjectionBackfill.Result result = DynamoDbProjectionBackfill.builder()
    .client(ddb)
    .applicator(applicator)
    .sourceTableName("Orders")
    .sourcePartitionKey("customerId")
    .sourceSortKey("orderId")
    .consistentRead(true)
    .pageSize(100)
    .build()
    .execute();
```

For production, record the stream position, start the consumer from it, run the backfill, and
then let the consumer drain changes after that position. A bare Scan alone cannot form a
race-free snapshot while source writes continue.

---

## 3. Read the summary (after projection has caught up)

```java
GetItemResponse response = client.getItem(GetItemRequest.builder()
    .tableName("OrdersByCustomer")
    .key(Collections.singletonMap("customerId",
         AttributeValue.builder().s("c1").build()))
    .build());

Map<String, AttributeValue> item = response.item();
long orderCount = Long.parseLong(item.get("orderCount").n());
long totalAmount = Long.parseLong(item.get("totalAmount").n());
double avgAmount = Double.parseDouble(item.get("avgAmount").n()); // stored AVG
long minAmount = Long.parseLong(item.get("minAmount").n());
long maxAmount = Long.parseLong(item.get("maxAmount").n());
```

---

## 4. Aggregation scenarios (playbook parity)

### COUNT all orders for one customer

- **Projection:** `AggregateDefinition.count()` grouped by `customerId`
- **Read:** `GetItem(customerId=c1)` → `orderCount`
- **Enhanced Queries:** `aggregate(COUNT, "orderId", "orderCount")` with key condition

### SUM of amounts for one customer

- **Projection:** `AggregateDefinition.sum("amount")`
- **Read:** `totalAmount` on the summary row
- **Enhanced Queries:** `aggregate(SUM, "amount", "totalAmount")`

### AVG of amounts for one customer

- **Projection:** `AggregateDefinition.avg("amount")` maintains shadow `_avg_sum_<alias>` /
  `_avg_cnt_<alias>` and writes a stored `avgAmount` after each apply
- **Read:** `GetItem` → `avgAmount` (no client division required)
- **Enhanced Queries:** `aggregate(AVG, ...)` at query time

### MIN / MAX of amounts

- **Projection:** `AggregateDefinition.min/max("amount")`
  - INSERT / non-invalidating MODIFY: conditional `SET` when the value is a new extreme
  - REMOVE / invalidating MODIFY: **recompute from source group** via `SourceGroupScanner`
    (extra Query RCUs on those events)
- **Read:** `minAmount` / `maxAmount` on the summary row
- **Enhanced Queries:** `aggregate(MIN/MAX, ...)` at query time for ad-hoc use

### Filtered COUNT + SUM (`amount >= 50` or `status = paid`)

```java
.field("largeOrders",
       AggregateDefinition.count(ProjectionPredicate.gte("amount", 50)))
.field("largeRevenue",
       AggregateDefinition.sum("amount", ProjectionPredicate.gte("amount", 50)))
```

### Multiple aggregates in one projection

Declare several `.field(...)` entries — one target row holds all counters/sums (same as EQ multi-`aggregate`).

### GROUP BY two fields (`customerId`, `region`)

Requires region on the **order** (or denormalized onto the source item), because projections are single-source:

```java
.groupBy("customerId", "region")
.target(TargetTable.of("OrdersByCustomerRegion", "customerId", "region"))
```

### Global aggregation (no GROUP BY)

```java
.groupBy() // empty → partition key value "ALL"
.target(TargetTable.of("OrdersGlobal", "pk"))
```

### Scan-all / top-N / HAVING across all customers

These are **read-time** analytics over many groups. Aggregates are already on the summary
table; HAVING / ORDER BY / pagination run against those rows (not against raw orders).

```java
// In-memory (ProjectionHarness) or after ScanAll via DynamoDbSummaryTableReader
SummaryPage page = harness.query(SummaryQuery.builder()
    .having(ProjectionPredicate.gte("orderCount", 10))
    .orderByAggregate("totalAmount", SortDirection.DESC)
    .limit(10)
    .build());

for (SummaryRow row : page.rows()) {
    System.out.println(row.key() + " -> " + row.aggregates());
}
if (page.hasMore()) {
    SummaryPage next = harness.query(SummaryQuery.builder()
        .having(ProjectionPredicate.gte("orderCount", 10))
        .orderByAggregate("totalAmount", SortDirection.DESC)
        .limit(10)
        .cursor(page.cursor())   // opaque offset after having+orderBy
        .build());
}
```

**Two pagination modes** (same split as the JS ODM projection design):

| Mode | API | Cursor meaning | Sort |
|---|---|---|---|
| Native DynamoDB key order | `DynamoDbSummaryTableReader.scanPage(limit, cursor)` | base64url(`LastEvaluatedKey`) | Table key order only — requires `ALLOW_SCAN` |
| Aggregate GSI ORDER BY | `queryByAggregateGsi(limit, cursor, scanForward)` | base64url(`LastEvaluatedKey`) | GSI SK = aggregate — OK under `STRICT_KEY_ONLY` |
| HAVING + ORDER BY (client) | `SummaryQuery` / `harness.query` / `reader.query` | opaque offset after filter+sort | Any aggregate — requires `ALLOW_SCAN` |

Declare a GSI for server-side aggregate sort:

```java
.field("totalAmount", AggregateDefinition.sum("amount"))
.aggregateGsi(AggregateGsi.of("byTotalAmount", "gsiPk", "ALL", "totalAmount"))
```

On apply, the projector SETs `gsiPk = "ALL"` so the GSI is queryable. Create the table GSI with PK=`gsiPk`, SK=`totalAmount`.

**Execution mode** (aligned in name with Enhanced Queries):

```java
ProjectionApplicator.builder()
    .executionMode(ProjectionExecutionMode.ALLOW_SCAN) // or STRICT_KEY_ONLY (default)
    ...
```

- `STRICT_KEY_ONLY` — GetItem / key Query / aggregate GSI Query only; rejects summary Scan and empty-`groupBy` MIN/MAX source Scan
- `ALLOW_SCAN` — permits those Scan paths (needed for `SummaryQuery` and global MIN/MAX recompute)

Notes:

- DynamoDB cannot natively `ORDER BY totalAmount` without a GSI whose sort key is that attribute.
- Offset cursors after HAVING/ORDER BY assume a stable snapshot of the filtered+sorted list between pages.
- Empty `groupBy` MIN/MAX invalidate uses a full source `Scan` (`DynamoDbSourceTableScanner`) and requires `ALLOW_SCAN`.

With Enhanced Queries: `ALLOW_SCAN` over source + `having` / `orderByAggregate` (expensive on the source).

---

## 5. Unit-test without AWS (harness)

```java
ProjectionHarness harness = ProjectionHarness.of(ordersByCustomer);

Map<String, Object> order = new HashMap<>();
order.put("customerId", "c1");
order.put("orderId", "o1");
order.put("amount", 100);
order.put("status", "paid");

harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order));

Map<String, Number> aggs =
    harness.getAggregates(Collections.singletonMap("customerId", "c1"));
assert aggs.get("orderCount").intValue() == 1;
```

---

## 6. Join materialization (INNER / LEFT / RIGHT / FULL)

Stream projections support the same join types as Enhanced Queries via a multi-source
materialized view. Default is `INNER`: one target row per matching child, with selected
parent attributes denormalized. Set `.joinType(...)` for outer joins.

Join keys are immutable in this implementation: a MODIFY changing either side's join key (or the
right-side sort key) is rejected as a permanent failure. Parent changes fan out synchronously,
with `JoinProjectionApplicator.Builder.maxFanOut(int)` bounded to 100 rows by default; select a
limit that fits the Lambda timeout and write-capacity budget, or use a separate reconciliation
workflow for higher-cardinality relationships.

Join writes carry the originating stable stream version (`_v` for child mutations and `_leftV`
for parent fan-out), rather than generating a new runtime value. Replaying the same stream event
therefore produces the same materialized write. Cross-shard/global ordering and multi-table
reconciliation remain eventual-consistency concerns; use a reconciler for stronger guarantees.

```java
JoinProjectionSpec customersOrders = JoinProjections.builder("CustomersOrdersJoin")
    .joinType(JoinType.INNER) // or LEFT / RIGHT / FULL
    .leftEntityType("Customer")
    .rightEntityType("Order")
    .leftJoinAttribute("customerId")
    .rightJoinAttribute("customerId")
    .rightSortKeyAttribute("orderId")
    .leftFields("name", "region")
    .rightFields("orderId", "amount")
    .target(TargetTable.of("CustomersOrdersJoin", "customerId", "orderId"))
    .build();

JoinProjectionHarness joinHarness = JoinProjectionHarness.of(customersOrders);
// Apply Customer then Order NormalizedRecords (or JoinProjectionApplicator + ParentResolver)
```

| Need | Approach |
|---|---|
| Cross-table join at **read** time | Enhanced Queries |
| Cross-table join as **write-time MV** | `JoinProjectionSpec` + applicator / harness (this PoC) |
| Co-located parent + children | Same-table / single-table design |

**Note:** left-only rows (LEFT/FULL) use sort key `__LEFT_ONLY__`
(`JoinProjectionApplyEngine.LEFT_ONLY_SORT_KEY`).

### Join MV pagination

Query one partition of the join table with DynamoDB LEK cursors (benchmark scenario #27):

```java
JoinMaterializedViewReader reader = JoinMaterializedViewReader.builder()
    .client(dynamoDbClient)
    .projection(customersOrders)
    .tableName("CustomersOrdersJoin")
    .build();

JoinProjectionHarness.JoinPage page = reader.queryPage("c1", 100, null);
while (page.hasMore()) {
    page = reader.queryPage("c1", 100, page.cursor());
}
```

In-memory tests use `JoinProjectionHarness.queryPage(joinKey, limit, offsetCursor)`.

Embed parent `leftFields` on child stream images to avoid `ParentResolver` GetItem on every Order event during backfill.

---

## 7. Consistency and operations notes

- Target rows are **eventually consistent** with the source (typical Streams+Lambda lag: ~1–4 seconds).
- Group-by attributes must be **immutable** on updates (mutating them throws `GroupKeyMutationException`).
- With `DynamoDbProjectionStateStore`, at-least-once delivery is protected by a transactional,
  per-source checkpoint kept in the customer-provisioned state table. The checkpoint key is
  namespaced by the projection name and `ProjectionSpec.generation()` and uses canonical hashed
  source/target identities; summary rows do not grow an unbounded version map.
- Change `ProjectionSpec.generation("2")` when deploying an incompatible projection definition.
- Conditional checkpoint failures on replay are treated as no-ops. Other transaction failures
  must be surfaced to Lambda so the record is retried or sent to the configured failure destination.
- MIN/MAX invalidate events (delete/update of a contributing value) trigger a source-group
  Query to recompute the extreme; this costs extra RCUs by design.

---

## 8. Benchmarks

See `ProjectionBenchmarkRunner` and `EC2_BENCHMARK_INSTRUCTIONS.md` for scenarios
aligned with Enhanced Queries (`count_orders_one_customer`, `sum_amount_one_customer`, …).
