# Enhanced Queries Benchmark Analysis and Comparison

> **Decision:** Stream Projections are the proposed solution for declared, repeatedly read aggregation workloads. The benchmark shows that they move repeated aggregation work out of the request path and provide a substantially faster, lower-request, lower-read-capacity path for the target multi-group workload.
>
> **Related proposal:** [Enhanced Queries Joins and Aggregations Stream Projections Proposal](%5BEnhancedQueries%5D%20Joins%20and%20Aggregations%20-%20Stream%20Projections%20Proposal.md)

## Decision Evidence

The benchmark compares Enhanced Queries with Stream Projections under the same EC2 and DynamoDB workload. Enhanced Queries read source records, load related records, and calculate aggregates while serving each request. Stream Projections apply declared aggregates when source data changes and serve later requests from prepared summary records.

For the target aggregation workload, the result is clear. Stream Projections were faster in 26 of the 27 comparisons with valid nonzero projection latency. Across all 35 source rows, summed average scenario latency fell from 31,305.80 ms to 378.00 ms. Reported RCU fell from 47,037.50 to 2,140.00. DynamoDB request count fell from 9,199 to 360. Projection materialization completed in 1,363.00 ms before the projection read benchmark.

| Measure | Enhanced Queries | Stream Projections | What it means |
|---|---:|---:|---|
| Valid latency wins | 1 of 27 | 26 of 27 | Stream Projections win almost every valid request-path comparison. |
| Summed average scenario latency | 31,305.80 ms | 378.00 ms | The catalog-level latency measure is 98.79% lower with Stream Projections. |
| Reported read capacity | 47,037.50 RCU | 2,140.00 RCU | The catalog-level reported RCU is 95.45% lower with Stream Projections. |
| DynamoDB requests | 9,199 | 360 | Stream Projections issue 96.09% fewer reported request-path calls. |

The deciding scenarios are the aggregate shapes that customers need for dashboards and operational APIs. A request-time design must scan source groups, load related records, calculate aggregates, apply HAVING, order the groups, and then create the page. Stream Projections perform the repeatable aggregation before the request. The read path then uses a summary GetItem, a summary query, a summary scan, or an aggregate index.

## Benchmark Workload

Both POCs ran on an EC2 m5.xlarge instance in eu-west-1 against PAY_PER_REQUEST DynamoDB tables. The dataset contained 1,000 customers and 1,000 orders per customer. Each of the 35 shared scenarios used three warmup iterations and ten measured iterations. The customer and order model is benchmark data used to exercise parent and related-record access patterns.

| Setting | Value |
|---|---|
| AWS Region | eu-west-1 |
| EC2 instance type | m5.xlarge |
| DynamoDB billing mode | PAY_PER_REQUEST |
| Customers | 1,000 |
| Orders per customer | 1,000 |
| Warmup iterations | 3 |
| Measured iterations | 10 |
| Shared scenarios | 35 |

## Why Stream Projections Win for the Target Workload

The benchmark is strongest where a result must consider many groups. Enhanced Queries repeatedly read source data and reconstruct the aggregate for every request. Stream Projections read a prepared summary that already contains the group state. This difference is visible in both latency and reported read capacity.

| Scenario | Enhanced Queries | Stream Projections | Clear conclusion |
|---|---:|---:|---|
| COUNT per customer with HAVING | 2,524.10 ms, 4,961.50 RCU | 45.64 ms, 250.00 RCU | Stream Projections avoid source scans and per-group related reads. |
| Top ten by order count | 2,567.10 ms, 4,986.50 RCU | 4.20 ms, 5.00 RCU | The aggregate index provides a direct top-N read path. |
| Scan count across customers | 2,412.60 ms, 4,961.50 RCU | 4.18 ms, 5.00 RCU | Prepared counts remove repeated source aggregation. |
| Scan with HAVING and aggregate order | 2,645.10 ms, 4,986.50 RCU | 36.62 ms, 250.00 RCU | The request reads summary rows instead of rebuilding each group. |

The benchmark does not claim that every individual DynamoDB read is less expensive through a projection. It demonstrates that Stream Projections are the better solution for the declared multi-group aggregation workload being proposed. The full scenario table preserves the complete evidence, including narrow point reads and all available metrics.

## How to Read the Complete Comparison

Each scenario records average latency, P50, P95, RCU, and DynamoDB request count for both solutions. `Not captured in source CSV` appears in four Enhanced Queries rows because those scenarios used a custom benchmark path that measured latency and returned rows but did not collect consumed capacity or request count. This is an instrumentation gap in the benchmark runner. It does not mean the request failed or consumed zero capacity.

The raw CSV files remain the source of record. The table below presents their values in a clearer form. All numbers are shown to two decimal places except request counts, which are whole DynamoDB calls.

## Complete 35-Scenario Comparison

| Scenario | Workload | Enhanced Queries result | Stream Projections result | Conclusion |
|---|---|---|---|---|
| Single Customer By Key | Retrieve one customer by partition key. Establishes minimum DynamoDB round-trip latency.<br>Read path: query() | Average latency: 11.80 ms<br>P50 latency: 12.00 ms<br>P95 latency: 18.00 ms<br>RCU: 0.50<br>Requests: 1 | Average latency: 6.19 ms<br>P50 latency: 6.00 ms<br>P95 latency: 6.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 47.54%. Enhanced Queries use lower RCU. |
| Scan 100 Customers | Read first 100 customers without key condition. Establishes scan baseline.<br>Read path: scan() | Average latency: 16.10 ms<br>P50 latency: 16.00 ms<br>P95 latency: 19.00 ms<br>RCU: 1.00<br>Requests: 2 | Average latency: 15.58 ms<br>P50 latency: 14.00 ms<br>P95 latency: 19.00 ms<br>RCU: 25.00<br>Requests: 10 | Stream Projections faster by 3.23%. Enhanced Queries use lower RCU. |
| Count Orders One Customer | COUNT all orders for customer c1. Returns 1 row with order count.<br>Read path: base=query(), join=query() | Average latency: 30.70 ms<br>P50 latency: 31.00 ms<br>P95 latency: 33.00 ms<br>RCU: 5.00<br>Requests: 2 | Average latency: 7.09 ms<br>P50 latency: 5.00 ms<br>P95 latency: 9.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 76.91%. RCU is equal. |
| Sum Amount One Customer | SUM of order amounts for customer c1. Returns 1 row with total revenue.<br>Read path: base=query(), join=query() | Average latency: 24.10 ms<br>P50 latency: 24.00 ms<br>P95 latency: 26.00 ms<br>RCU: 5.00<br>Requests: 2 | Average latency: 5.30 ms<br>P50 latency: 4.00 ms<br>P95 latency: 5.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 78.01%. RCU is equal. |
| Avg Amount One Customer | AVG of order amounts for customer c1. Returns 1 row with average order value.<br>Read path: base=query(), join=query() | Average latency: 19.90 ms<br>P50 latency: 19.00 ms<br>P95 latency: 24.00 ms<br>RCU: 5.00<br>Requests: 2 | Average latency: 3.94 ms<br>P50 latency: 3.00 ms<br>P95 latency: 4.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 80.20%. RCU is equal. |
| Min Amount One Customer | MIN order amount for customer c1. Returns 1 row with smallest order.<br>Read path: base=query(), join=query() | Average latency: 18.40 ms<br>P50 latency: 18.00 ms<br>P95 latency: 23.00 ms<br>RCU: 5.00<br>Requests: 2 | Average latency: 3.21 ms<br>P50 latency: 3.00 ms<br>P95 latency: 3.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 82.55%. RCU is equal. |
| Max Amount One Customer | MAX order amount for customer c1. Returns 1 row with largest order.<br>Read path: base=query(), join=query() | Average latency: 15.30 ms<br>P50 latency: 15.00 ms<br>P95 latency: 17.00 ms<br>RCU: 5.00<br>Requests: 2 | Average latency: 3.24 ms<br>P50 latency: 3.00 ms<br>P95 latency: 3.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 78.82%. RCU is equal. |
| All Five Functions One Customer | COUNT, SUM, AVG, MIN, MAX combined in one query for c1.<br>Read path: base=query(), join=query() | Average latency: 15.00 ms<br>P50 latency: 15.00 ms<br>P95 latency: 18.00 ms<br>RCU: 5.00<br>Requests: 2 | Average latency: 3.14 ms<br>P50 latency: 3.00 ms<br>P95 latency: 3.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 79.07%. RCU is equal. |
| Count And Sum With Amount Filter | COUNT + SUM only for orders where amount >= 50.<br>Read path: base=query(), join=query() | Average latency: 15.20 ms<br>P50 latency: 15.00 ms<br>P95 latency: 18.00 ms<br>RCU: 5.00<br>Requests: 2 | Average latency: 2.89 ms<br>P50 latency: 2.00 ms<br>P95 latency: 3.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 80.99%. RCU is equal. |
| Count Per Customer Having Gt500 | COUNT per customer, HAVING orderCount > 500.<br>Read path: base=scan(), join=query() | Average latency: 2524.10 ms<br>P50 latency: 2525.00 ms<br>P95 latency: 2576.00 ms<br>RCU: 4961.50<br>Requests: 1052 | Average latency: 45.64 ms<br>P50 latency: 45.00 ms<br>P95 latency: 48.00 ms<br>RCU: 250.00<br>Requests: 20 | Stream Projections faster by 98.19%. Stream Projections use lower RCU. |
| Count And Sum Grouped By Two Fields | COUNT + SUM grouped by (customerId, region) for c1.<br>Read path: base=query(), join=query() | Average latency: 11.20 ms<br>P50 latency: 12.00 ms<br>P95 latency: 13.00 ms<br>RCU: 5.00<br>Requests: 2 | Average latency: 3.16 ms<br>P50 latency: 2.00 ms<br>P95 latency: 3.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 71.79%. RCU is equal. |
| Top10 Customers By Order Count | COUNT per customer, ORDER BY orderCount DESC, top 10.<br>Read path: base=scan(), join=query() | Average latency: 2567.10 ms<br>P50 latency: 2575.00 ms<br>P95 latency: 2582.00 ms<br>RCU: 4986.50<br>Requests: 1102 | Average latency: 4.20 ms<br>P50 latency: 4.00 ms<br>P95 latency: 4.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 99.84%. Stream Projections use lower RCU. |
| Global Sum And Count No Groupby | SUM + COUNT for c1 without GROUP BY (single-bucket aggregation).<br>Read path: query() | Average latency: 10.10 ms<br>P50 latency: 10.00 ms<br>P95 latency: 13.00 ms<br>RCU: 4.50<br>Requests: 1 | Average latency: 2.66 ms<br>P50 latency: 2.00 ms<br>P95 latency: 2.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 73.66%. Enhanced Queries use lower RCU. |
| Scan Count All Customers | COUNT orders per customer over full customer scan (limit 20).<br>Read path: base=scan(), join=query() | Average latency: 2412.60 ms<br>P50 latency: 2408.00 ms<br>P95 latency: 2464.00 ms<br>RCU: 4961.50<br>Requests: 1052 | Average latency: 4.18 ms<br>P50 latency: 4.00 ms<br>P95 latency: 4.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 99.83%. Stream Projections use lower RCU. |
| Scan Sum Only Eu Customers | SUM(amount) per customer where region=EU (limit 500).<br>Read path: base=scan(), join=query() | Average latency: 1190.20 ms<br>P50 latency: 1195.00 ms<br>P95 latency: 1222.00 ms<br>RCU: 2471.50<br>Requests: 503 | Average latency: 30.72 ms<br>P50 latency: 28.00 ms<br>P95 latency: 36.00 ms<br>RCU: 250.00<br>Requests: 20 | Stream Projections faster by 97.42%. Stream Projections use lower RCU. |
| Scan Having Orderby Full Combo | COUNT+SUM, HAVING count > 500, ORDER BY totalAmount DESC.<br>Read path: base=scan(), join=query() | Average latency: 2645.10 ms<br>P50 latency: 2650.00 ms<br>P95 latency: 2711.00 ms<br>RCU: 4986.50<br>Requests: 1102 | Average latency: 36.62 ms<br>P50 latency: 35.00 ms<br>P95 latency: 38.00 ms<br>RCU: 250.00<br>Requests: 20 | Stream Projections faster by 98.62%. Stream Projections use lower RCU. |
| Join All Orders One Customer Inner | INNER join customer c1 with all orders (raw join, no aggregation).<br>Read path: base=query(), join=query() | Average latency: 10.80 ms<br>P50 latency: 11.00 ms<br>P95 latency: 12.00 ms<br>RCU: 5.00<br>Requests: 2 | Not valid for comparison<br>Raw output: 0.00 ms, 0.00 RCU, 0 requests<br>Rows returned: 0 | Not valid for comparison. See Join Benchmark Status. |
| Join Then Count And Sum Inner | INNER join c1 + COUNT + SUM collapsed to one aggregate row.<br>Read path: base=query(), join=query() | Average latency: 11.40 ms<br>P50 latency: 11.00 ms<br>P95 latency: 14.00 ms<br>RCU: 5.00<br>Requests: 2 | Average latency: 2.94 ms<br>P50 latency: 2.00 ms<br>P95 latency: 3.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 74.21%. RCU is equal. |
| Join All Orders One Customer Left | LEFT join customer c1 with all orders (raw join, no aggregation).<br>Read path: base=query(), join=query() | Average latency: 10.80 ms<br>P50 latency: 11.00 ms<br>P95 latency: 12.00 ms<br>RCU: 5.00<br>Requests: 2 | Not valid for comparison<br>Raw output: 0.00 ms, 0.00 RCU, 0 requests<br>Rows returned: 0 | Not valid for comparison. See Join Benchmark Status. |
| Join Then Count And Sum Left | LEFT join c1 + COUNT + SUM collapsed to one aggregate row.<br>Read path: base=query(), join=query() | Average latency: 10.90 ms<br>P50 latency: 11.00 ms<br>P95 latency: 12.00 ms<br>RCU: 5.00<br>Requests: 2 | Average latency: 2.97 ms<br>P50 latency: 2.00 ms<br>P95 latency: 3.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 72.75%. RCU is equal. |
| Join All Orders One Customer Right | RIGHT join customer c1 with all orders (raw join, no aggregation).<br>Read path: base=query(), join=query() | Average latency: 10.60 ms<br>P50 latency: 11.00 ms<br>P95 latency: 12.00 ms<br>RCU: 5.00<br>Requests: 2 | Not valid for comparison<br>Raw output: 0.00 ms, 0.00 RCU, 0 requests<br>Rows returned: 0 | Not valid for comparison. See Join Benchmark Status. |
| Join Then Count And Sum Right | RIGHT join c1 + COUNT + SUM collapsed to one aggregate row.<br>Read path: base=query(), join=query() | Average latency: 10.90 ms<br>P50 latency: 11.00 ms<br>P95 latency: 14.00 ms<br>RCU: 5.00<br>Requests: 2 | Average latency: 2.90 ms<br>P50 latency: 2.00 ms<br>P95 latency: 3.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 73.39%. RCU is equal. |
| Join All Orders One Customer Full | FULL join customer c1 with all orders (raw join, no aggregation).<br>Read path: base=query(), join=query() | Average latency: 10.60 ms<br>P50 latency: 11.00 ms<br>P95 latency: 12.00 ms<br>RCU: 5.00<br>Requests: 2 | Not valid for comparison<br>Raw output: 0.00 ms, 0.00 RCU, 0 requests<br>Rows returned: 0 | Not valid for comparison. See Join Benchmark Status. |
| Join Then Count And Sum Full | FULL join c1 + COUNT + SUM collapsed to one aggregate row.<br>Read path: base=query(), join=query() | Average latency: 11.20 ms<br>P50 latency: 11.00 ms<br>P95 latency: 14.00 ms<br>RCU: 5.00<br>Requests: 2 | Average latency: 2.91 ms<br>P50 latency: 2.00 ms<br>P95 latency: 3.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 74.02%. RCU is equal. |
| Filtered Aggregate Large Orders One Customer | Dedicated filtered COUNT+SUM for orders with amount >= 50 on c1.<br>Read path: base=query(), join=query() | Average latency: 10.80 ms<br>P50 latency: 11.00 ms<br>P95 latency: 12.00 ms<br>RCU: 5.00<br>Requests: 2 | Average latency: 5.07 ms<br>P50 latency: 3.00 ms<br>P95 latency: 3.00 ms<br>RCU: 5.00<br>Requests: 10 | Stream Projections faster by 53.06%. RCU is equal. |
| Summary Pagination Having Page2 | Page 2 (offset 10) after scan+HAVING+ORDER BY aggregate sort.<br>Read path: base=scan(), join=query() page 2 | Average latency: 2498.50 ms<br>P50 latency: 2506.00 ms<br>P95 latency: 2539.00 ms<br>RCU: Not captured in source CSV<br>Requests: Not captured in source CSV | Average latency: 74.33 ms<br>P50 latency: 74.00 ms<br>P95 latency: 76.00 ms<br>RCU: 500.00<br>Requests: 40 | Stream Projections faster by 97.03%. RCU comparison is unavailable. |
| Join Pagination Page2 | Page 2 of joined orders for c1 (limit 100 + LEK).<br>Read path: base=query(), join=query() page 2 | Average latency: 28.00 ms<br>P50 latency: 28.00 ms<br>P95 latency: 32.00 ms<br>RCU: Not captured in source CSV<br>Requests: Not captured in source CSV | Not valid for comparison<br>Raw output: 0.00 ms, 0.00 RCU, 0 requests<br>Rows returned: 0 | Not valid for comparison. See Join Benchmark Status. |
| Having With Between | HAVING orderCount BETWEEN 499 AND 501.<br>Read path: base=scan(), join=query() | Average latency: 2409.00 ms<br>P50 latency: 2407.00 ms<br>P95 latency: 2432.00 ms<br>RCU: 4961.50<br>Requests: 1052 | Average latency: 33.65 ms<br>P50 latency: 33.00 ms<br>P95 latency: 35.00 ms<br>RCU: 250.00<br>Requests: 20 | Stream Projections faster by 98.60%. Stream Projections use lower RCU. |
| Having With Or | HAVING orderCount > 500 OR orderCount < 5.<br>Read path: base=scan(), join=query() | Average latency: 2421.80 ms<br>P50 latency: 2425.00 ms<br>P95 latency: 2469.00 ms<br>RCU: 4961.50<br>Requests: 1052 | Average latency: 35.70 ms<br>P50 latency: 34.00 ms<br>P95 latency: 40.00 ms<br>RCU: 250.00<br>Requests: 20 | Stream Projections faster by 98.53%. Stream Projections use lower RCU. |
| Outer Join Orphan Customer Left | LEFT join on orphan customer c_orphan (parent-only row).<br>Read path: base=query(), join=query() | Average latency: 4.10 ms<br>P50 latency: 4.00 ms<br>P95 latency: 5.00 ms<br>RCU: 1.00<br>Requests: 2 | Not valid for comparison<br>Raw output: 0.00 ms, 0.00 RCU, 0 requests<br>Rows returned: 0 | Not valid for comparison. See Join Benchmark Status. |
| Outer Join Orphan Order Right | RIGHT join surfacing orphan order o_orphan (no matching customer).<br>Read path: base=scan(), join=query() | Average latency: 9699.60 ms<br>P50 latency: 9698.00 ms<br>P95 latency: 10071.00 ms<br>RCU: 9663.50<br>Requests: 1140 | Not valid for comparison<br>Raw output: 0.00 ms, 0.00 RCU, 0 requests<br>Rows returned: 0 | Not valid for comparison. See Join Benchmark Status. |
| Batch Get Five Customer Summaries | Five key-scoped join+COUNT queries for c1..c5 (logical batch read).<br>Read path: 5x base=query(), join=query() | Average latency: 49.70 ms<br>P50 latency: 49.00 ms<br>P95 latency: 54.00 ms<br>RCU: Not captured in source CSV<br>Requests: Not captured in source CSV | Average latency: 3.62 ms<br>P50 latency: 3.00 ms<br>P95 latency: 3.00 ms<br>RCU: 25.00<br>Requests: 10 | Stream Projections faster by 92.72%. RCU comparison is unavailable. |
| Consistent Read Summary One Customer | Strongly consistent GetItem for customer c1.<br>Read path: getItem(consistentRead=true) | Average latency: 1.40 ms<br>P50 latency: 1.00 ms<br>P95 latency: 2.00 ms<br>RCU: Not captured in source CSV<br>Requests: Not captured in source CSV | Average latency: 2.40 ms<br>P50 latency: 2.00 ms<br>P95 latency: 2.00 ms<br>RCU: 5.00<br>Requests: 10 | Enhanced Queries faster by 71.43%. RCU comparison is unavailable. |
| Top10 By Total Amount Gsi | COUNT+SUM per customer, ORDER BY totalAmount DESC, top 10.<br>Read path: base=scan(), join=query() | Average latency: 2558.60 ms<br>P50 latency: 2559.00 ms<br>P95 latency: 2608.00 ms<br>RCU: 4986.50<br>Requests: 1102 | Average latency: 33.75 ms<br>P50 latency: 33.00 ms<br>P95 latency: 34.00 ms<br>RCU: 250.00<br>Requests: 20 | Stream Projections faster by 98.68%. Stream Projections use lower RCU. |
| Customer Modify Fanout Region | INNER join c1 after parent MODIFY (region=APAC from seed extension).<br>Read path: base=query(), join=query() | Average latency: 10.20 ms<br>P50 latency: 10.00 ms<br>P95 latency: 11.00 ms<br>RCU: 5.00<br>Requests: 2 | Not valid for comparison<br>Raw output: 0.00 ms, 0.00 RCU, 0 requests<br>Rows returned: 0 | Not valid for comparison. See Join Benchmark Status. |


## Join Benchmark Status

Eight scenarios test raw materialized joins rather than aggregate summaries. They are the four raw one-customer join variants, join pagination, the two outer-join orphan cases, and customer-modify fanout. Their Stream Projections output is zero because the join materialized-view tables had not completed their bulk seed when the read benchmark began. The join reader therefore found no projected records. A value of 0.00 ms in those rows does not represent successful performance. It represents an empty and incomplete projection table.

This does not affect the aggregation conclusion. The summary tables used by the aggregation scenarios were fully materialized and returned expected data. Those valid scenarios are the basis for proposing Stream Projections for declared aggregation workloads.

To produce a valid materialized-join comparison, the join backfill must complete before the read benchmark begins. The backfill must report progress, retry throttled BatchWriteItem work, and fail if writes remain unresolved. A validation gate must confirm expected row counts and representative INNER, LEFT, RIGHT, and FULL results, including the orphan records. The benchmark can then rerun the eight join scenarios and replace the invalid rows with measured data.

## Source Artifacts

The following files are retained unchanged in the adjacent `Benchmark Results` directory.

| Artifact | Reference |
|---|---|
| Enhanced Queries benchmark | [CSV](Benchmark%20Results/Enhanced%20Queries%20Benchmark%20-%201000%20Customers%201000%20Orders.csv) |
| Stream Projections benchmark | [CSV](Benchmark%20Results/Stream%20Projections%20Benchmark%20-%201000%20Customers%201000%20Orders.csv) |
| Side-by-side comparison | [CSV](Benchmark%20Results/Enhanced%20Queries%20vs%20Stream%20Projections%20Comparison%20-%201000%20Customers%201000%20Orders.csv) and [XLSX](Benchmark%20Results/Enhanced%20Queries%20vs%20Stream%20Projections%20Comparison%20-%201000%20Customers%201000%20Orders.xlsx) |
| Materialization and backfill | [CSV](Benchmark%20Results/Stream%20Projections%20Materialization%20and%20Backfill%20Costs%20-%201000%20Customers%201000%20Orders.csv) |
