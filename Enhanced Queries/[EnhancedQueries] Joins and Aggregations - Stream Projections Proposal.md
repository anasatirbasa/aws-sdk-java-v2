# Enhanced Queries (Joins & Aggregations) in AWS SDK Java v2 - Feature Proposal

> **Purpose:** This proposal evaluates two working approaches for joins and aggregations over DynamoDB data in the Java SDK. Enhanced Queries perform the work while serving a request. Stream Projections maintain declared aggregate and single-hop join materialized views as source data changes. The proposal recommends Stream Projections for repeated, declared operational read shapes.
>
> **Review expectation:** Stakeholders are asked to validate the Stream Projections direction, its operational model, and its first delivery scope. Enhanced Queries remain a fully evaluated alternative.

### Stakeholders

Owner: Ana-Maria Satirbasa

Primary reviewers: DynamoDB Service Team and AWS Java SDK Team

Secondary reviewers: Service teams considering complex DynamoDB query patterns

### Document Phase

**DRAFT (Design Review Pending)**

## Problem Statement

### What is the problem and why solve it

DynamoDB is designed for predictable key-based access. It does not offer a server-side join engine or a complete model for GROUP BY, HAVING, and aggregate ordering. Yet product APIs, dashboards, and operational workflows often need results that combine records or summarize many groups. Teams currently solve this independently with client-side orchestration, custom Streams pipelines, or separate analytics systems.

The SDK should give customers a reusable abstraction with a clear contract for performance, capacity, freshness, retries, pagination, and operational ownership. That prevents every team from rebuilding the same data-access pattern with different safeguards.

### Why does this problem exist

DynamoDB distributes records by partition key and exposes Query, Scan, and index operations. It does not execute a relational plan across tables. A request that combines records, groups results, applies aggregate filters or ordering, and returns a page must either compose source reads at request time or read a result prepared earlier.

### How are customers solving this problem

Customers often read a primary item and then issue related reads for each matching key. Other teams build summary tables with DynamoDB Streams and Lambda or another worker. Larger historical reporting workloads move data to S3, Athena, or Redshift. Each solution is valid, but each requires the customer to independently design data correctness, capacity boundaries, monitoring, and recovery.

### Has a similar problem existed/been solved before

Client-side orchestration and write-time materialization are established DynamoDB patterns. The missing piece is a reusable, tested SDK capability that gives customers an explicit contract for each pattern. The demand references in Appendix A1 show recurring requests for joins, aggregates, and GROUP BY behavior over DynamoDB data.

### Why this abstraction layer vs alternatives

The proposed SDK capability serves low-latency operational requests with declared aggregation needs. Enhanced Queries provide a flexible source-fresh option, while Stream Projections prepare repeated aggregate results before the request arrives. For the tested multi-group workloads, Stream Projections are the necessary request-path approach because they avoid repeatedly scanning sources, loading related records, and aggregating in the application.

Direct client-side composition keeps the infrastructure small but shifts correctness and cost control into every customer application. DynamoSQL and other external SQL layers introduce a separate service boundary and do not provide the SDK-native projection lifecycle proposed here. Athena, Redshift, and S3-based analytics remain the right choice for broad historical analysis, cross-source reporting, and workloads that accept longer latency. They complement rather than replace Stream Projections for near-real-time operational rollups.

## Tenets

### 1. Key-first execution on both sides

The selected design uses key-based access where possible. A known group reads one summary row. A top-N request reads a declared aggregate index. Summary scans remain explicit because their cost grows with the number of groups. Enhanced Queries follow the same principle, but become more expensive when an access path is not key-aligned.

### 2. Relational semantics without a server-side join or aggregate engine

DynamoDB does not provide the relational plan. Enhanced Queries construct it at read time from DynamoDB operations and JVM processing. Stream Projections construct the required aggregate state as source records change. The recommended design moves repeating aggregate work to the write path so each request can use a small and predictable read path.

### 3. Projection pushdown as part of the performance and resource contract

Both approaches request only the attributes needed for keys, grouping, filters, and aggregate maintenance. Narrow projections reduce transferred data and client processing. They do not by themselves reduce DynamoDB billing, which depends on stored item size. The capability must report actual service consumption rather than infer it from selected attributes.

## Intended Customer Experience

### What are you launching

The proposed launch is a Stream Projections capability for declared DynamoDB aggregate and single-hop join materialized views. Customers define source entities, group attributes, aggregate fields, selected carried-forward attributes, join behavior where required, and target tables. The runtime applies source changes to materialized state and exposes prepared reads through GetItem, BatchGetItem, summary queries, aggregate indexes, and join-table queries.

The implemented scope includes COUNT, SUM, AVG, MIN, and MAX; one-field and multi-field grouping; filtered aggregates; HAVING; aggregate ordering; limits; and pagination where the selected summary access path supports it. It also includes single-hop INNER, LEFT, RIGHT, and FULL join materialized views with DynamoDB cursor pagination.

### Why should I use Enhanced Queries

Enhanced Queries and Stream Projections solve the same customer need with different tradeoffs. Enhanced Queries are suitable when source freshness and ad hoc query shape matter more than prepared-read performance. The POC supports read-time joins, grouping, aggregates, HAVING, ordering, pagination, and explicit scan policy without Streams, summary tables, an apply worker, or backfill.

Stream Projections are the proposed solution when the application repeatedly reads declared aggregate shapes. They replace source scans and in-memory aggregation with reads of prepared summaries. The benchmark shows that this is decisive for multi-group HAVING, aggregate ordering, and top-N requests. Customers accept projection lag and lifecycle operations in exchange for predictable request latency and substantially lower read-path work.

### Configuration and API shape

The Stream Projections API is declarative. `ProjectionSpec` identifies the source type, grouping keys, aggregate definitions, target table, optional aggregate GSI, and carried-forward fields. `JoinProjectionSpec` identifies the two source entities, join keys, join type, selected fields, and join target. Projection and join applicators process source changes with idempotent, version-aware updates. Readers select the smallest access path that can answer the requested result.

Enhanced Queries use a fluent specification that declares tables, joins, filters, grouping, aggregates, ordering, execution mode, and limits. Its API remains useful as the evaluated source-time alternative and clarifies the contrast with the proposed projection experience.

### How this feature relates to other AWS surfaces

The capability composes existing DynamoDB features. It uses source Streams, DynamoDB target and projection-state tables, indexes, Lambda or an equivalent worker, and the Enhanced Client model. It does not create a new AWS service or provision infrastructure. Customers create and configure source Streams, target tables and GSIs, projection-state tables, Lambda, event-source mappings, IAM roles, DLQs, and alarms through CDK, CloudFormation, Terraform, or equivalent infrastructure-as-code.

## Proof of Concept

Two POCs were evaluated. The Enhanced Queries POC proved that typed builders, client-side joins, in-memory aggregation, and explicit scan controls can be implemented in the SDK. The Stream Projections POC proved that declared summaries, aggregate reads, projection materialization, and shared benchmark scenarios can be implemented against DynamoDB.

Both POCs ran against the same EC2 and DynamoDB workload. The comparison is the evidence for this proposal. References appear in Appendix A5.

## Summary of Design Decisions

The proposed solution is Stream Projections for declared aggregation and single-hop join workloads. Source changes flow through DynamoDB Streams to a runtime that routes records to aggregate or join applicators. Aggregate application calculates old and new group contributions and updates the matching summary row. Join application maintains one target row per match and represents outer-join-only rows where appropriate. The design includes explicit backfill, retry-safe application, idempotency, version protection, freshness reporting, capacity reporting, and rebuild procedures.

Enhanced Queries are not the primary implementation for repeated declared shapes. They remain valuable for source-fresh and bounded ad hoc requests. The measured request-path latency, request count, and read capacity are much higher for repeated scan-heavy aggregation. Stream Projections instead move recurring aggregate and join work to the write path.

## High Level Design

### Architecture overview

A Stream Projection starts with a declared aggregate or join specification. Source writes create DynamoDB Streams records with `NEW_AND_OLD_IMAGES`, a stable `_v` version, and an entity type. `StreamProjectionLambdaAdapter` decodes the Lambda event, invokes `StreamProjectionRuntime`, and returns partial batch failures for the event-source mapping to retry. The runtime routes aggregate records to `ProjectionApplicator` and join records to `JoinProjectionApplicator`.

Source tables remain the system of record. Summary and join target tables are eventually updated representations. A customer-provisioned projection-state table tracks source-item versions, namespaced by projection name and generation. Conditional target writes and stable source versions make retries idempotent. The system measures and exposes materialization lag so customers can make an informed freshness decision.

The library does not create DynamoDB tables, Streams, GSIs, Lambda functions, event-source mappings, queues, roles, or alarms. Customers provision them in infrastructure-as-code. The required deployment sequence is: create targets and state table; enable source Streams with `NEW_AND_OLD_IMAGES`; deploy the worker and its mapping with `ReportBatchItemFailures`; grant least-privilege stream, target, and state-table access; then backfill historical data and monitor catch-up before serving projection reads.

### Join execution (sync and async)

Enhanced Queries execute joins by reading source and related records at request time. Their sync and async engines use the same logical stages with different delivery models. This explains why key-aligned joins are practical while scan-backed joins are costly.

Stream Projections implement joins as single-hop write-time materialized views. `JoinProjectionSpec` supports INNER, LEFT, RIGHT, and FULL semantics. Join target rows contain the join key, right-side sort key, and selected denormalized fields; they can be read with a DynamoDB Query and LastEvaluatedKey cursor. Parent changes can fan out to matching child rows within the configured operational limit.

Join keys are immutable in this implementation. A change to either side's join key is rejected rather than silently moving materialized rows. Per-record idempotency protects retries, but DynamoDB Streams do not provide a global order across tables or shards. A strict cross-table reconciliation guarantee therefore requires a separate reconciliation subsystem and is not provided by this PoC.

### Aggregation execution (sync and async)

The selected design maintains COUNT, SUM, AVG, MIN, and MAX as source changes arrive. Inserts add a contribution. Deletes remove it. Updates remove the old contribution before adding the new one. AVG is stored using maintained sum/count state. A deleted or changed MIN/MAX contributor can require a bounded source-group recomputation; an empty-group global MIN/MAX recomputation requires an explicitly allowed source scan. Group-by attributes are immutable: a mutation is a `GroupKeyMutationException` rather than an implicit row move.

Readers do not recompute aggregates from source items. A known group uses GetItem. Several known groups use BatchGetItem. Ordered or filtered sets use a summary query or declared aggregate index. This is the main reason the design produces a smaller and more predictable read path.

### Consistency and snapshot semantics

Enhanced Queries read source tables at request time, but multiple DynamoDB operations do not create a cross-table snapshot. Strong consistency improves an individual base-table read but cannot make the full multi-read result atomic. This is acceptable when small time-based differences are tolerable and source freshness matters.

Stream Projections provide a different guarantee. A result is current after Streams delivery and successful application to the summary. The API and telemetry must expose that freshness boundary. A consumer that requires the latest source state reads source tables instead of assuming a projection is immediately current.

### GSI selection for joined-side lookups

Enhanced Queries select a table key or matching GSI for joined-side access, with an explicit override when a caller needs deterministic selection. This remains part of the evaluated POC behavior.

For Stream Projections, index design is declared with the projection. A top-N aggregate access pattern uses an aggregate index with a predictable partition strategy and an aggregate-value sort key. The projection specification makes that requirement explicit before deployment.

### Threading model

Enhanced Queries use bounded concurrency for related reads and JVM aggregation. Unbounded fan-out is unsafe because one logical request can trigger many DynamoDB calls.

Stream Projection application uses the Lambda event-source mapping or an equivalent customer-owned worker for bounded concurrency and retry control. Retries use the event-source mapping's partial-batch failure contract; unrecoverable records are handled through the customer-configured failure destination or DLQ. Reader operations stay small because expensive work is not repeated by each caller.

### Pagination

Enhanced Queries can expose cursor pagination for row reads, but aggregation output must be fully computed before a final page exists. Stream Projections paginate prepared summary rows. The continuation token is tied to the selected summary query or aggregate index.

### Error handling

Enhanced Queries expose validation, execution-policy, missing-key, and intermediate-result errors so expensive or invalid source-time behavior is visible to callers. Stream Projections expose invalid-specification, immutable-group-key, immutable-join-key, stale-version, missing-target, failed-apply, and backfill failures. `StreamProjectionLambdaAdapter` returns retryable event IDs through `ReportBatchItemFailures`; the event-source mapping and configured DLQ own delivery recovery.

### Limitations of the Design

Stream Projections require declared access patterns, target and state tables, Streams processing, customer-provisioned infrastructure, explicit backfill, and ongoing monitoring. They do not provide immediate source-table freshness or a cross-table snapshot. Their total cost depends on source-write volume, aggregate and join fan-out, target/index size, aggregate-read volume, and recovery activity.

The PoC supports single-hop joins only. It does not include chained joins, mutable join keys, automatic infrastructure creation, automatic cutover from a historical backfill to a live stream, or a strict durable cross-table reconciler. Backfill is explicit and bounded; customers must choose and operate their own cutover and reconciliation procedure.

Enhanced Queries avoid those write-side concerns but can consume substantial read capacity and JVM memory for broad scans and joins. The selected design accepts projection complexity because the target workload repeatedly reads aggregate results and benefits from moving that work out of the request path.

## Telemetry and Observability

The feature reports source record count, aggregate and join apply successes and failures, retries, stale-version count, materialization lag, backfill progress, summary and join read latency, read capacity, write capacity, DynamoDB request count, and rebuild duration. This information makes freshness and lifecycle cost observable. Customers connect these metrics to their own alarms and DLQ monitoring.

The benchmark shows why read latency alone is insufficient. The first production validation must capture complete materialization write-capacity and request telemetry, then assess it against realistic read and write ratios.

## Packaging Strategy

Stream Projections remain in the existing `dynamodb-enhanced-projection` Maven artifact and Java namespace. This keeps the projection runtime, Lambda adapter, readers, state store, backfill utility, and test harness together while leaving the core `dynamodb-enhanced` API unchanged. Customers opt in by adding this existing artifact; no separate Lambda-only artifact is required.

## Benchmarking

The benchmark compares the two POCs on the same workload. It is evidence for this design decision, not a universal DynamoDB price or latency promise. Both runs used an EC2 m5.xlarge instance in eu-west-1 with PAY_PER_REQUEST tables, 1,000 customers, 1,000 orders per customer, three warmup iterations, and ten measured iterations across 35 scenarios.

Enhanced Queries read source tables and perform joins, grouping, filtering, and ordering in the request path. Stream Projections read materialized summaries through GetItem, summary scans, summary queries, and aggregate indexes. Materialization completed in 1,363.00 ms before the projection read benchmark.

Enhanced Queries produced 31,305.80 ms when average scenario latency was summed. Stream Projections produced 378.00 ms. Of the 27 comparisons with valid nonzero projection latency, Stream Projections were faster in 26. The one exception was a narrow strongly consistent point read, where Enhanced Queries took 1.40 ms and Stream Projections took 2.40 ms.

The largest differences occur in the aggregate shapes that motivate the proposal. COUNT with a HAVING threshold took 2,524.10 ms with Enhanced Queries and 45.64 ms with Stream Projections. Top ten by order count took 2,567.10 ms and 4.20 ms. The combined scan, HAVING, and aggregate-ordering scenario took 2,645.10 ms and 36.62 ms.

Enhanced Queries reported 47,037.50 RCUs across the 31 rows with populated capacity values and 9,199 requests. Stream Projections reported 2,140.00 RCUs and 360 requests. Some simple direct reads use the same or fewer RCUs with Enhanced Queries. The target multi-group workloads reverse that pattern because prepared summaries avoid most repeated source access. The complete scenario analysis is in [the benchmark analysis](%5BEnhancedQueries%5D%20Benchmark%20Analysis%20and%20Comparison.md).

The evidence has boundaries. Materialization write-capacity and request telemetry were not captured. Four Enhanced Queries RCU values are unavailable. Eight raw join and outer-join projection rows reported zero values because the join materialized views were not fully populated. Those rows are excluded from the recommendation until they are re-seeded and verified.

## Rollout and Rollback Strategy

The rollout starts with declared aggregations and controlled backfill. The first release validates Streams consumption, summary writes, idempotent retries, lag monitoring, and rebuild behavior using customer-shaped workloads. Broader adoption follows after production-shaped write rates, throttling, partial failures, and recovery behavior are measured.

Rollback does not change source tables. Customers can stop reading projection tables, disable the apply path, and return to the existing access pattern while retaining source data. A feature flag or application-level configuration isolates the projection reader so a summary issue can be contained quickly.

## Impact of the Design

The selected design changes the operating model more than the source data model. It introduces summary targets, Streams permissions, an apply role, metrics, backfill, and recovery procedures. It reduces request-path CPU, read capacity, and latency for the target aggregation workload.

Security remains based on existing IAM and DynamoDB controls. The apply role receives only the permissions needed to read source Streams and update projection targets. Reader roles access only the required summary tables and indexes. No third-party service or new control plane is required.

## Appendix

### Appendix A1: Customer Demand References

Representative customer discussions include [manual joins in application code](https://stackoverflow.com/questions/39073883/how-to-join-tables-in-dynamodb), [COUNT and GROUP BY](https://stackoverflow.com/questions/77851193/dynamodb-count-aggregate-group-by-pk), and [DynamoDB Streams aggregations](https://aws.amazon.com/blogs/database/build-aggregations-for-amazon-dynamodb-tables-using-amazon-dynamodb-streams/).

### Appendix A2: QueryExpressionBuilder and Conditions

The Enhanced Queries POC uses a fluent builder for base tables, join stages, key conditions, filters, grouping, aggregates, HAVING, ordering, projections, consistency, intermediate-row limits, and pagination. Conditions are evaluated in memory except for the key condition that selects a DynamoDB Query path.

### Appendix A3: Example Specifications

The Enhanced Queries POC includes key-scoped and composite-key joins, chained joins, aggregate HAVING queries, pagination, and bounded execution. The Stream Projections POC includes declared customer summaries, multi-field summaries, global summaries, aggregate ordering, filtered aggregates, and single-hop join materialized views. Appendix A5 contains the implementation references.

### Appendix A4: Cross-SDK Parity Snapshot

AWS SDKs provide typed DynamoDB access, keys, conditions, and projections. They do not generally ship a first-class cross-table join and GROUP BY layer. The proposed Java capability is an SDK-level abstraction over existing DynamoDB primitives rather than a new DynamoDB service feature.

### Appendix A5: Proof of Concept Links

| POC artifact | Reference |
|---|---|
| Enhanced Queries implementation | https://github.com/anasatirbasa/aws-sdk-java-v2/tree/feature/ddb-enhanced-queries/services-custom/dynamodb-enhanced/src/main/java/software/amazon/awssdk/enhanced/dynamodb/query |
| Enhanced Queries POC | https://github.com/aws/aws-sdk-java-v2/pull/6813 |
| Enhanced Queries Playbook | https://github.com/gorelov1/ddb-endava/blob/feature/491_enhanced-queries-joins-and-aggregations/DesignDocuments/Java/%5BPlaybook%5D%20Enhanced%20Queries%20-%20Joins%20and%20Aggregations.md |
| Stream Projections implementation | `services-custom/dynamodb-enhanced-projection/src/main/java/software/amazon/awssdk/enhanced/dynamodb/projection` |
| Stream Projections playbook | `services-custom/dynamodb-enhanced-projection/PROJECTION_PLAYBOOK.md` |
| Stream Projections feature parity | `services-custom/dynamodb-enhanced-projection/FEATURE_PARITY.md` |

### Appendix A6: Execution Pipeline and Components

Enhanced Queries read source pages, load related records, merge rows, group values, apply HAVING, order results, and return a page. Stream Projections decode source change records, calculate aggregate deltas or join writes, perform guarded target updates, and serve summary or join targets through the selected read path. The first concentrates work at request time. The second concentrates materialization before the request.

### Appendix A7: Detailed Design Decisions

The selected design requires declared aggregate or single-hop join shapes, idempotent apply logic, immutable group and join keys, controlled MIN/MAX recomputation, a projection generation for incompatible definition changes, and explicit backfill. Enhanced Queries require explicit scan policy, bounded intermediate results, and clear limits for broad joins.

### Appendix A8: Telemetry and Logging

Projection telemetry records apply attempts, successes, failures, retries, stale versions, write capacity, request counts, lag, backfill progress, and summary-read behavior. Enhanced Queries telemetry records base and related reads, scans, latency, request count, and consumed capacity.

### Appendix A9: Benchmark Scenarios and Results

The complete 35-scenario comparison, methodology, RCU interpretation, and evidence limits are in [the benchmark analysis](%5BEnhancedQueries%5D%20Benchmark%20Analysis%20and%20Comparison.md). The raw sources are retained unchanged in `Benchmark Results`.

| Artifact | Reference |
|---|---|
| Enhanced Queries benchmark | [CSV](Benchmark%20Results/Enhanced%20Queries%20Benchmark%20-%201000%20Customers%201000%20Orders.csv) |
| Stream Projections benchmark | [CSV](Benchmark%20Results/Stream%20Projections%20Benchmark%20-%201000%20Customers%201000%20Orders.csv) |
| Side-by-side comparison | [CSV](Benchmark%20Results/Enhanced%20Queries%20vs%20Stream%20Projections%20Comparison%20-%201000%20Customers%201000%20Orders.csv) and [XLSX](Benchmark%20Results/Enhanced%20Queries%20vs%20Stream%20Projections%20Comparison%20-%201000%20Customers%201000%20Orders.xlsx) |
| Materialization and backfill | [CSV](Benchmark%20Results/Stream%20Projections%20Materialization%20and%20Backfill%20Costs%20-%201000%20Customers%201000%20Orders.csv) |

### Appendix A10: Telemetry Scenario Examples

A summary read reports one logical request, latency, consumed capacity, and access path. A projection apply reports source sequence information, target group or join key, outcome, retry count, lag, consumed write capacity, and whether the record was ignored as stale. A backfill reports progress, duration, failures, and restart position. A Lambda deployment also monitors partial-batch failures, configured DLQ depth, and source-stream iterator age.

### Appendix A11: Glossary

| Term | Definition |
|---|---|
| Enhanced Queries | Read-time client-side joins and aggregations over DynamoDB source tables |
| Stream Projections | Write-time materialization of declared aggregate summaries from DynamoDB Streams |
| Summary row | Materialized row storing aggregate values for one group |
| Materialization lag | Time between a source change and its visible summary update |
| Read capacity units | DynamoDB read capacity reported for a request |
| Backfill | Creation or reconstruction of summary state from existing source data |

### Appendix A12: Execution Flowcharts

The Enhanced Queries flow is source read, related read, merge, group, filter, order, and return. The Stream Projections flow is source change, Lambda adapter or worker, aggregate delta or join materialization, guarded target/state update, lag measurement, and summary or join read. Detailed diagrams remain with the POC documentation as the execution model evolves.

## Meeting Notes
