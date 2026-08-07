# Stream Projection — Feature Parity (Enhanced Queries)

Experimental PoC under `services-custom/dynamodb-enhanced-projection`.

## Summary

Benchmark suite: **35 scenarios** in [`benchmark-scenarios.json`](benchmark-scenarios.json) (EQ + stream projection CSV parity).

| Capability | Enhanced Queries | Stream Projection |
|---|---|---|
| COUNT / SUM / AVG / MIN / MAX | Read-time over joined source rows | Precomputed on summary MV |
| groupBy (≤4 dims) | Yes | Yes (`ProjectionSpec.groupBy`) |
| Write-time filter (`filterBase`) | N/A (read filter) | `ProjectionPredicate` on aggregate fields |
| Read-time HAVING | `Condition` on aggregates | `SummaryQuery.having(Condition)` + legacy `ProjectionPredicate` |
| ORDER BY aggregate | Yes | Yes; GSI fast path when ORDER BY matches `aggregateGsi` |
| ORDER BY dimension | Yes | Scan + in-memory sort |
| limit / pagination | Offset + LEK cursors | Offset cursors (HAVING path) or DynamoDB LEK (Scan/GSI) |
| `project()` | Yes | Not supported (summary schema is fixed) |
| Ad-hoc `where` / `filterJoined` | Yes | Not supported (use carry-forward + HAVING or write-time predicate) |
| Joins | Read-time | Write-time join MV (`JoinProjectionSpec`) |
| Join pagination | Query + LEK | `JoinMaterializedViewReader.queryPage` |
| Chained joins | Preview | Out of scope (single-hop MV only) |
| `maxIntermediateRows` | Yes | N/A (aggregates bounded by group cardinality) |
| Consistent read on summary | N/A | `DynamoDbSummaryTableReader.builder().consistentRead(true)` |

## MIN / MAX lifecycle

Stream projection maintains MIN/MAX with conditional updates when a new extreme arrives.
When an extreme source item is removed or changes, the apply engine marks the alias for
**recompute** and scans the in-memory source snapshot (harness) or source table (production
`SourceGroupScanner`) to refresh the value.

AVG is stored as shadow SUM/COUNT fields (`_avg_*`) and refreshed on each additive apply.

## Consistent read

`DynamoDbSummaryTableReader.builder().consistentRead(true)` enables strongly consistent
GetItem / Query / Scan / BatchGetItem on summary tables (2× read RCU). Default is `false`.

Strong reads on the summary table do **not** remove DynamoDB Streams pipeline lag relative
to the source table.

## Carry-forward attributes

`ProjectionSpec.Builder.carryForward("region", ...)` copies selected source attributes onto
each summary row at apply time. They participate in HAVING (`SummaryRow.asHavingItem`) and
are included in auto `ProjectionExpression` on Scan/GSI reads.

Use this for benchmark scenario #15 (`region=EU`) and similar read-time filters that EQ
applies on the base table.

## HAVING

`SummaryQuery.having(Condition)` uses the same condition operators as Enhanced Queries
(`eq`, `gt`, `gte`, `lt`, `lte`, `between`, `contains`, `beginsWith`, `and`, `or`, `not`,
nested dot-paths).

Legacy `SummaryQuery.having(ProjectionPredicate)` remains for simple filters.

Simple HAVING conditions may be pushed to DynamoDB `FilterExpression` on Scan/Query.

**Benchmark parity:** HAVING threshold defaults to `min(500, ordersPerCustomer - 1)` so
500×500 datasets match EQ row counts (override with `HAVING_ORDER_COUNT_THRESHOLD`).

## Pagination

| Mode | API | Cursor |
|------|-----|--------|
| HAVING + ORDER BY | `SummaryQuery` + `DynamoDbSummaryTableReader.query` | Offset (`ProjectionCursors.encodeOffset`) |
| Scan pages | `scanPage(limit, cursor)` | DynamoDB LEK |
| Top-N by aggregate | `queryByAggregateGsi` or GSI fast path in `query` | DynamoDB LEK |
| Batch point reads | `batchGetItems(keys)` | N/A (≤100 keys per request) |
| Parallel backfill | N/A | `ProjectionApplicator.applyRecords` + `batchWrites(true)`; join `BatchWriteItem` |

## Benchmark extension scenarios (#25–35)

| Scenario | Stream projection read path |
|---|---|
| `filtered_aggregate_large_orders_one_customer` | GetItem `largeOrders` / `largeRevenue` (write-time predicate) |
| `summary_pagination_having_page2` | `SummaryQuery` offset cursor page 2 |
| `join_pagination_page2` | `JoinMaterializedViewReader.queryPage` LEK |
| `having_with_between` / `having_with_or` | `SummaryQuery.having(Condition…)` |
| `outer_join_orphan_customer_left` | Query join MV `__LEFT_ONLY__` row |
| `outer_join_orphan_order_right` | Query orphan child join row |
| `batch_get_five_customer_summaries` | `DynamoDbSummaryTableReader.batchGetItems` |
| `consistent_read_summary_one_customer` | `consistentRead(true)` GetItem |
| `top10_by_total_amount_gsi` | GSI / in-memory ORDER BY `totalAmount` |
| `customer_modify_fanout_region` | Join MV row after parent MODIFY fan-out |

## Read optimizations

- **ProjectionExpression** — auto-derived from `SummaryQuery` + `ProjectionSpec` on Scan/GSI Query
- **FilterExpression** — simple HAVING pushdown on Scan/GSI Query
- **BatchGetItem** — `DynamoDbSummaryTableReader.batchGetItems` for multi-key summary reads

## Write-path notes

- **Production idempotency** — use `DynamoDbProjectionStateStore` so a source checkpoint and
  additive summary update are committed in one DynamoDB transaction. The state table is
  customer-provisioned; it is namespaced by `ProjectionSpec.generation()` and does not place an
  unbounded source-version map on the summary row.
- **Lambda adapter** — `StreamProjectionLambdaAdapter` accepts the standard DynamoDB Streams
  Lambda event and returns a partial-batch failure response. Enable `ReportBatchItemFailures` on
  the event-source mapping. The library does not create the Lambda, mapping, DLQ, tables, or IAM.
- **Source versions** — `_v` must be written by the source application and be stable on retries;
  production stream decoding rejects missing versions.
- **Parallel backfill** — `BUILD_PARALLELISM` (default: CPU count); no `INCLUDE_JOINS` skip flag
- **Aggregate GSI** — declare `aggregateGsi` for ORDER BY on one aggregate alias (e.g. `orderCount`)
- **Join backfill batching** — `JoinProjectionApplicator.builder().batchWrites(true)` uses BatchWriteItem
- **Aggregate backfill parallelism** — `ProjectionApplicator.builder().batchWrites(true).batchExecutor(...)` parallelizes `applyRecords` (UpdateItem path)

## MV (materialized view)

Target DynamoDB tables maintained by Streams apply (`OrdersByCustomer`, join MVs, etc.).

## Benchmark backends

| `BENCHMARK_BACKEND` | Behavior |
|---|---|
| `memory` (default) | In-memory harness; measures post-materialization read cost |
| `dynamodb` | Live summary tables via `DynamoDbSummaryTableReader`; skips in-memory build |

DynamoDB mode env vars: `PROJECTION_SUMMARY_TABLE`, `PROJECTION_SUMMARY_REGION_TABLE`,
`AWS_REGION`, optional `PROJECTION_CONSISTENT_READ=true`.

Compare CSVs: `scripts/compare_benchmarks.py eq.csv projection.csv`
