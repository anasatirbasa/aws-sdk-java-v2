# DynamoDB Enhanced Projection (PoC)

Experimental **stream-projection** apply engine for DynamoDB. Maintains `count` / `sum` /
stored `avg` / `min` / `max` rollups and **join materializations** (`INNER` / `LEFT` /
`RIGHT` / `FULL`) from change events (DynamoDB Streams) onto target tables using
conditional writes and a version-map idempotency model (aggregates ported from the
TypeScript `dynamodb-odm` projection design; AVG/MIN/MAX and joins extended for this Java PoC).

> This is a **proof of concept**. Packaging recommendation: standalone AWS Labs artifact
> (same guidance as Enhanced Queries), not a permanent expansion of core `dynamodb-enhanced`.

## Module contents

| Package / file | Role |
|---|---|
| `projection.ProjectionSpec` / `Projections` | Declarative aggregate projection definition |
| `projection.JoinProjections` / `JoinProjectionSpec` / `JoinType` | Join MV definition |
| `projection.ProjectionApplyEngine` / `ProjectionApplicator` | Aggregate apply path |
| `projection.JoinProjectionApplyEngine` / `JoinProjectionApplicator` | Join apply path |
| `projection.ProjectionHarness` / `JoinProjectionHarness` | In-memory harnesses |
| `projection.SummaryQuery` / `SummaryQueryEngine` | Read-time HAVING, ORDER BY, limit, cursor |
| `projection.DynamoDbSummaryTableReader` | Scan / aggregate-GSI Query; execution-mode gated |
| `projection.ProjectionExecutionMode` | `STRICT_KEY_ONLY` / `ALLOW_SCAN` |
| `projection.AggregateGsi` | Declare GSI for ORDER BY aggregate |
| `projection.DynamoDbSourceTableScanner` | Full source Scan for empty-`groupBy` MIN/MAX |
| `projection.ProjectionCursors` | Opaque cursors (offset + `LastEvaluatedKey`) |
| `projection.StreamRecordDecoder` | Streams `Record` → `NormalizedRecord` |
| `PROJECTION_PLAYBOOK.md` | Usage examples (playbook parity) |
| `COMPARISON_BENCHMARK.md` | EQ vs projection side-by-side |
| `EC2_BENCHMARK_INSTRUCTIONS.md` | Benchmark how-to |
| `ProjectionBenchmarkRunner` | A9 scenario runner + CSV |

## Quick start — aggregates

```java
ProjectionSpec spec = Projections.builder("OrdersByCustomer")
    .sourceEntityType("Order")
    .groupBy("customerId")
    .target(TargetTable.of("OrdersByCustomer", "customerId"))
    .field("orderCount", AggregateDefinition.count())
    .field("totalAmount", AggregateDefinition.sum("amount"))
    .build();

ProjectionApplicator applicator = ProjectionApplicator.builder()
    .client(DynamoDbClient.create())
    .projection(spec)
    .sourcePartitionKey("customerId")
    .sourceSortKey("orderId")
    .build();
```

## Quick start — join

```java
JoinProjectionSpec join = JoinProjections.builder("CustomersOrdersJoin")
    .joinType(JoinType.INNER) // LEFT / RIGHT / FULL also supported
    .leftEntityType("Customer")
    .rightEntityType("Order")
    .leftJoinAttribute("customerId")
    .rightJoinAttribute("customerId")
    .rightSortKeyAttribute("orderId")
    .leftFields("name", "region")
    .rightFields("orderId", "amount")
    .target(TargetTable.of("CustomersOrdersJoin", "customerId", "orderId"))
    .build();
```

See [PROJECTION_PLAYBOOK.md](PROJECTION_PLAYBOOK.md) for full examples (including HAVING /
ORDER BY / pagination vs JS ODM), AVG/MIN/MAX guidance, joins, and infrastructure notes.

**Benchmarks (HLD Appendix A9):**
- [COMPARISON_BENCHMARK.md](COMPARISON_BENCHMARK.md) — run Enhanced Queries + projections with the same env and compare CSVs
- [EC2_BENCHMARK_INSTRUCTIONS.md](EC2_BENCHMARK_INSTRUCTIONS.md) — projection scenario mapping

## Build & test

```bash
mvn -pl services-custom/dynamodb-enhanced-projection test
```

Includes unit tests and DynamoDB Local integration tests (`ProjectionApplicatorIT`).

## Benchmark (local, 35 scenarios)

```bash
export CUSTOMER_COUNT=100 ORDERS_PER_CUSTOMER=100
export BENCHMARK_OUTPUT_FILE=/tmp/projection_benchmark.csv
mvn test-compile exec:java -pl services-custom/dynamodb-enhanced-projection \
  -Dexec.mainClass="software.amazon.awssdk.enhanced.dynamodb.functionaltests.ProjectionBenchmarkRunner" \
  -Dexec.classpathScope=test
```

Canonical scenario keys: [`benchmark-scenarios.json`](benchmark-scenarios.json).
