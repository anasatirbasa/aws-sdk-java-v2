# Side-by-side benchmark: Enhanced Queries vs Stream Projections

## Validated benchmark command

The supported EC2 workflow is now the single
[`run_ec2_benchmark_suite.sh`](scripts/run_ec2_benchmark_suite.sh) command documented in
[`EC2_BENCHMARK_INSTRUCTIONS.md`](EC2_BENCHMARK_INSTRUCTIONS.md). It uses a common run ID and
configuration, requires all 35 scenarios to pass, rejects blank metrics, verifies the seeded joins,
and writes a CSV, color-coded XLSX workbook, lifecycle CSV, and manifest into one run directory.

The remaining manual paths are useful for development only. Do not use them as decision evidence.

Run **both** PoCs with the **same dataset size, warmup, iterations, and region** so you can
compare CSV rows by stable `Scenario Id`.

## What you are comparing

| | Enhanced Queries | Stream projections |
|---|---|---|
| Runner | `EnhancedQueryBenchmarkRunner` | `ProjectionBenchmarkRunner` |
| Module | `services-custom/dynamodb-enhanced` | `services-custom/dynamodb-enhanced-projection` |
| Cost paid | **At read** (Query/Scan + in-JVM aggregate) | **At write** (Streams apply); read is GetItem/Scan on **summary** |
| Fair EC2 compare | Live DynamoDB source tables | Summary + join MV already caught up, then GetItem/Query/Scan (or in-memory harness as lower bound) |
| Joins | Supported as a Phase 2 preview | Supported via materialized join MV (`JoinProjectionHarness` / applicator) |

**Important:** The projection in-memory harness measures post-materialization read cost only
(GetItem/SummaryQuery stand-ins). It is **not** the same as EQ’s live DynamoDB RCU. Use it for
architecture contrast and CI; use EC2 GetItem on real summary tables for production-like
projection read latency.

---

## Shared benchmark environment

| Property | Value |
|---|---|
| Instance | EC2 **m5.xlarge** (or same class for both runs) |
| Region | Same as DynamoDB (HLD used **eu-west-1**; use one region for both) |
| Dataset | **1000** customers × **1000** orders (`CUSTOMER_COUNT` / `ORDERS_PER_CUSTOMER`) |
| Billing | DynamoDB **PAY_PER_REQUEST** |
| Warmup | **3** (`BENCHMARK_WARMUP=3`) |
| Measured iterations | **10** (`BENCHMARK_ITERATIONS=10`) |
| Scenario set | Shared aggregation and join scenarios with the same stable identifiers in both CSVs |

Export once (both shells / both runs):

```bash
export AWS_REGION=eu-west-1          # or your chosen region — keep identical
export CUSTOMER_COUNT=1000
export ORDERS_PER_CUSTOMER=1000
export BENCHMARK_WARMUP=3
export BENCHMARK_ITERATIONS=10
export MAVEN_OPTS="-Xmx4g"
```

---

## Scenario checklist

| # | Scenario | EQ | Projection |
|---|---|---|---|
| 1 | `single_customer_by_key` | Query customer | GetItem summary c1 |
| 2 | `scan_100_customers` | Scan customers | First 100 summary rows |
| 3 | `count_orders_one_customer` | join+COUNT | GetItem `orderCount` |
| 4 | `sum_amount_one_customer` | join+SUM | GetItem `totalAmount` |
| 5 | `avg_amount_one_customer` | join+AVG | GetItem stored `avgAmount` |
| 6 | `min_amount_one_customer` | join+MIN | GetItem `minAmount` |
| 7 | `max_amount_one_customer` | join+MAX | GetItem `maxAmount` |
| 8 | `all_five_functions_one_customer` | five aggs | One GetItem all five |
| 9 | `count_and_sum_with_amount_filter` | filter amount≥50 | Filtered fields on summary |
| 10 | `count_per_customer_having_gt500` | HAVING > 500 | `SummaryQuery` HAVING |
| 11 | `count_and_sum_grouped_by_two_fields` | groupBy 2 | GetItem (c1, region) |
| 12 | `top10_customers_by_order_count` | ORDER BY + limit 10 | `SummaryQuery` ORDER BY |
| 13 | `global_sum_and_count_no_groupby` | COUNT+SUM for c1 orders | GetItem c1 count+sum |
| 14 | `scan_count_all_customers` | scan+COUNT (limit 20) | Scan summaries (limit 20) |
| 15 | `scan_sum_only_eu_customers` | region=EU | Filter EU summaries |
| 16 | `scan_having_orderby_full_combo` | HAVING+ORDER BY+limit | `SummaryQuery` combo |
| 17 | `join_all_orders_one_customer` | INNER join | Query join MV for c1 |
| 18 | `join_then_count_and_sum` | join+aggs | GetItem c1 COUNT+SUM summary |

---

## Path A — Same machine, quick local compare (in-memory projection + DynamoDB Local or AWS for EQ)

Use when you want matching **scenario names** and iteration settings quickly.

### 1) Enhanced Queries (forks repo)

```bash
cd /Users/anasatirbasa/work/amazon/repo/forks/aws-sdk-java-v2

# Install SNAPSHOT deps once if needed (codegen plugins + dynamodb)
# mvn -pl codegen-maven-plugin,codegen-lite-maven-plugin,services/dynamodb -am install -DskipTests

export CUSTOMER_COUNT=1000
export ORDERS_PER_CUSTOMER=1000
export BENCHMARK_WARMUP=3
export BENCHMARK_ITERATIONS=10
export INSTANCE_TYPE=m5.xlarge
export BENCHMARK_OUTPUT_FILE=/tmp/enhanced_queries_benchmark_1000_customers_1000_orders.csv

# Prefer real DynamoDB for comparable numbers (see Path B).
# For DynamoDB Local / seed, follow services-custom/dynamodb-enhanced/EC2_BENCHMARK_INSTRUCTIONS.md
export AWS_REGION=eu-west-1
export CUSTOMERS_TABLE=customers_large
export ORDERS_TABLE=orders_large
# First time only:
# export CREATE_AND_SEED=true

mvn test-compile exec:java -pl services-custom/dynamodb-enhanced \
  -Dexec.mainClass="software.amazon.awssdk.enhanced.dynamodb.functionaltests.query.EnhancedQueryBenchmarkRunner" \
  -Dexec.classpathScope=test
```

### 2) Stream projections (same env vars)

```bash
cd /Users/anasatirbasa/work/amazon/repo/forks/aws-sdk-java-v2

export CUSTOMER_COUNT=1000
export ORDERS_PER_CUSTOMER=1000
export BENCHMARK_WARMUP=3
export BENCHMARK_ITERATIONS=10
export BENCHMARK_OUTPUT_FILE=/tmp/stream_projections_benchmark_1000_customers_1000_orders.csv
export MAVEN_OPTS="-Xmx4g"

mvn test-compile exec:java -pl services-custom/dynamodb-enhanced-projection \
  -Dexec.mainClass="software.amazon.awssdk.enhanced.dynamodb.functionaltests.ProjectionBenchmarkRunner" \
  -Dexec.classpathScope=test
```

Projection runner prints materialization time and then the read scenarios.

### 3) Join CSVs on scenario name

```bash
# Example: compare avgMs by scenario (requires both CSVs with a scenario column)
python3 - <<'PY'
import csv
eq = {r["Scenario Id"]: r for r in csv.DictReader(open("/tmp/enhanced_queries_benchmark_1000_customers_1000_orders.csv"))}
# projection CSV has approach,scenario,...
pr = {}
with open("/tmp/stream_projections_benchmark_1000_customers_1000_orders.csv") as f:
    for r in csv.DictReader(f):
        pr[r["scenario"]] = r
print(f"{'Scenario':40} {'Enhanced Queries':>18} {'Stream Projections':>20}")
for s in eq:
    e = eq[s].get("avgMs") or eq[s].get("avg_ms")
    p = pr.get(s, {}).get("avgMs", "n/a")
    print(f"{s:40} {e:>12} {p:>12}")
PY
```

(Adjust column names if your EQ CSV header differs slightly.)

---

## Path B — Same EC2, production-like (recommended for the decision)

Follow the shared benchmark topology: one `m5.xlarge` in the same Region as DynamoDB.

### Shared setup

1. Create `customers_large` + `orders_large` (see EQ `EC2_BENCHMARK_INSTRUCTIONS.md`).
2. Create projection summary tables (e.g. `OrdersByCustomer` PK=`customerId`, plus optional GSI
   `byTotalAmount` PK=`gsiPk` SK=`totalAmount`) — see projection playbook.
3. Seed **once** with EQ runner (`CREATE_AND_SEED=true`, 1000×1000).
4. Materialize projections (Streams+Lambda **or** offline apply) until caught up.
5. Run the Enhanced Queries benchmark → `/tmp/enhanced_queries_benchmark_1000_customers_1000_orders.csv`
6. Run projection reads against summary → `/tmp/stream_projections_benchmark_1000_customers_1000_orders.csv`
7. Keep `CUSTOMER_COUNT`, `ORDERS_PER_CUSTOMER`, `BENCHMARK_WARMUP=3`,
   `BENCHMARK_ITERATIONS=10`, `AWS_REGION` identical.

### How to decide

| If you care about… | Prefer |
|---|---|
| Ad-hoc / join / no infra | Enhanced Queries |
| Fixed dashboards, hot GetItem, stable RCU on read | Stream projections |
| Scan-wide HAVING/ORDER BY | EQ pays full join+scan; projection pays summary Scan (much smaller) |
| Joins | Enhanced Queries at read time and projections through a write-time join MV |

Always report **both** projection materialization (build) time and read latency — otherwise the
comparison is incomplete.

---

---

## Full benchmark (35 scenarios, 1000×1000)

Both runners always execute **all 35 scenarios** from [`benchmark-scenarios.json`](benchmark-scenarios.json).
There is no `INCLUDE_JOINS` skip flag. Seed performance is optimized (join-key index, parallel workers,
embedded parent fields, four join types in one pass). Target: **under 10 minutes** in-memory at 1000×1000.

```bash
export CUSTOMER_COUNT=1000 ORDERS_PER_CUSTOMER=1000
export BUILD_PARALLELISM=8          # tune for instance (m5.xlarge: 8–16)
export BENCHMARK_WARMUP=3 BENCHMARK_ITERATIONS=10
export BENCHMARK_OUTPUT_FILE=/tmp/stream_projections_benchmark_1000_customers_1000_orders.csv
export MAVEN_OPTS="-Xmx4g"

mvn test-compile exec:java -pl services-custom/dynamodb-enhanced-projection \
  -Dexec.mainClass="software.amazon.awssdk.enhanced.dynamodb.functionaltests.ProjectionBenchmarkRunner" \
  -Dexec.classpathScope=test
```

For DynamoDB EC2 backfill (BatchWriteItem precomputed rows, not stream simulation):

```bash
export BENCHMARK_BACKEND=dynamodb
export BENCHMARK_BULK_SEED=true
export PROJECTION_GLOBAL_TABLE=OrdersGlobal
export PROJECTION_JOIN_TABLE_INNER=CustomersOrdersJoinInner
# PROJECTION_JOIN_TABLE_LEFT, _RIGHT, _FULL similarly
```

| Mode | Scenarios | Typical in-memory build (after optimizations) |
|---|---|---|
| `200×200` full suite | 35 | **~200 ms** seed (`ProjectionBenchmarkSeedPerfTest` gate, 60 s timeout) |
| `1000×1000` full suite (stream simulation) | 35 | **3–10 min** |
| `1000×1000` with `BENCHMARK_BULK_SEED=true` | 35 | **~2–5 min** in-memory; **~2–5 min** DDB EC2 |

Optional CI gate at full scale (not run in default `mvn test`):

```bash
export BENCHMARK_SEED_PERF_1000=true MAVEN_OPTS="-Xmx8g"
mvn -pl services-custom/dynamodb-enhanced-projection test \
  -Dtest=ProjectionBenchmarkSeedPerfTest#bulkSeed1000x1000CompletesUnderTenMinutes \
  -Dspotbugs.skip=true -Dcheckstyle.skip=true
```

The test prints `buildMs` for documentation; target **under 600 s** (10 min).

Compare the CSVs with [`scripts/compare_benchmarks.py`](scripts/compare_benchmarks.py) and the same catalog file.
The script writes the raw comparison CSV plus a color-coded Excel workbook. Lower latency,
capacity, and request count are highlighted green for the winning solution and red for the
other solution. Numeric measurements are stored with two decimal places in the CSV; Excel
displays them using its locale settings.

```bash
python3 services-custom/dynamodb-enhanced-projection/scripts/compare_benchmarks.py \
  /tmp/enhanced_queries_benchmark_1000_customers_1000_orders.csv \
  /tmp/stream_projections_benchmark_1000_customers_1000_orders.csv \
  --catalog services-custom/dynamodb-enhanced-projection/benchmark-scenarios.json \
  --out-dir /tmp
```

This additionally creates `/tmp/enhanced_queries_vs_stream_projections_comparison_1000_customers_1000_orders.xlsx`.

---

## Smoke run (smaller dataset)

```bash
export CUSTOMER_COUNT=100 ORDERS_PER_CUSTOMER=100
export BENCHMARK_WARMUP=3 BENCHMARK_ITERATIONS=10
# then run both runners as above
```

Scale to 1000×1000 for comparable numbers.
