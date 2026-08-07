# Stream Projection Benchmark Instructions

Aligned with the shared benchmark scenario identifiers used by `EnhancedQueryBenchmarkRunner`.
For running **both** PoCs together, see [COMPARISON_BENCHMARK.md](COMPARISON_BENCHMARK.md).

## Validated EC2 suite

Use `scripts/run_ec2_benchmark_suite.sh` for decision-quality results. It creates fresh projection
tables for one run, creates the `byTotalAmount` GSI, performs the validated backfill, verifies all
join targets, runs both 35-scenario read benchmarks, rejects incomplete CSV rows, and writes the
comparison CSV, XLSX workbook, lifecycle CSV, and run manifest to one new directory.

The source `CUSTOMERS_TABLE` and `ORDERS_TABLE` must already contain the declared dataset. The suite
adds the deterministic benchmark extension records required by outer-join and fan-out scenarios.

```bash
cd ~/aws-sdk-java-v2
export AWS_REGION=eu-west-1
export INSTANCE_TYPE=m5.xlarge
export DYNAMODB_BILLING_MODE=PAY_PER_REQUEST
export CUSTOMERS_TABLE=customers_large
export ORDERS_TABLE=orders_large
export CUSTOMER_COUNT=1000
export ORDERS_PER_CUSTOMER=1000
export BENCHMARK_WARMUP=3
export BENCHMARK_ITERATIONS=10
export BENCHMARK_RUN_ID=20260805T143000Z
export BENCHMARK_OUTPUT_ROOT="$PWD/benchmark-results"

bash services-custom/dynamodb-enhanced-projection/scripts/run_ec2_benchmark_suite.sh
```

Use a new `BENCHMARK_RUN_ID` for every run. The suite refuses to overwrite an existing directory
or reuse an existing projection target table. It deliberately does not delete DynamoDB tables.

> **Important:** Enhanced Queries pays cost **at read**. Stream projections pay cost **at write**
> (apply/Streams/Lambda) and then read a summary row. Report both materialization (build) time
> and read-scenario latency.

---

## Shared benchmark defaults

```bash
export CUSTOMER_COUNT=1000
export ORDERS_PER_CUSTOMER=1000
export BENCHMARK_WARMUP=3
export BENCHMARK_ITERATIONS=10
export INSTANCE_TYPE=m5.xlarge
export BENCHMARK_OUTPUT_FILE=/tmp/stream_projections_benchmark_1000_customers_1000_orders.csv
export MAVEN_OPTS="-Xmx4g"
export BENCHMARK_DDB_WRITE_PARALLELISM=8   # write threads per table; 4 join tables run in parallel
export BENCHMARK_DDB_WRITE_PAUSE_MS=0      # no pause between customer batches (PAY_PER_REQUEST)
# Optional: export BUILD_PARALLELISM=8  # parallel seed workers (default: CPU count)
```

---

## Quick local run (in-memory harness, no AWS)

```bash
cd /path/to/aws-sdk-java-v2   # forks checkout

mvn test-compile exec:java -pl services-custom/dynamodb-enhanced-projection \
  -Dexec.mainClass="software.amazon.awssdk.enhanced.dynamodb.functionaltests.ProjectionBenchmarkRunner" \
  -Dexec.classpathScope=test
```

Smoke (smaller dataset, same warmup/iterations):

```bash
export CUSTOMER_COUNT=100 ORDERS_PER_CUSTOMER=100
export BENCHMARK_WARMUP=3 BENCHMARK_ITERATIONS=10
```

---

See [benchmark-scenarios.json](benchmark-scenarios.json) for all 35 stable scenario identifiers. The generated CSV also contains a readable scenario name and description. Legacy join names map to `_inner` variants in `compare_benchmarks.py`.

---

## EC2 / real DynamoDB (optional)

1. Use the same EC2 instance, Region, and source tables as the Enhanced Queries run.
2. Create summary table(s) and materialize via Streams/Lambda (playbook).
3. Keep `CUSTOMER_COUNT` / `ORDERS_PER_CUSTOMER` / warmup / iterations identical.
4. Run with live summary reads:

```bash
export BENCHMARK_BACKEND=dynamodb
export PROJECTION_SUMMARY_TABLE=OrdersByCustomer
export PROJECTION_SUMMARY_REGION_TABLE=OrdersByCustomerRegion
export AWS_REGION=eu-west-1
# optional: export PROJECTION_CONSISTENT_READ=true
```

5. Benchmark GetItem, summary Scan, and GSI Query. The runner requests consumed capacity and writes
   `stream_projections_benchmark_1000_customers_1000_orders.csv`. Use
   `scripts/compare_benchmarks.py` to create the side-by-side result.

See [COMPARISON_BENCHMARK.md](COMPARISON_BENCHMARK.md) for the full side-by-side procedure.

For DynamoDB EC2 backfill, set `BENCHMARK_BACKEND=dynamodb` and `BENCHMARK_BULK_SEED=true` to
BatchWriteItem precomputed summary and join rows (tables must exist). Env: `PROJECTION_SUMMARY_TABLE`,
`PROJECTION_SUMMARY_REGION_TABLE`, `PROJECTION_GLOBAL_TABLE`, `PROJECTION_JOIN_TABLE_INNER`, etc. Record
the materialization and backfill result as
`stream_projections_materialization_and_backfill_costs_1000_customers_1000_orders.csv`.
