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
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.utils.Validate;

/**
 * Applies a {@link ProjectionSpec} to DynamoDB stream records by issuing conditional
 * {@code UpdateItem} calls (additive COUNT/SUM/AVG shadows, AVG refresh, MIN/MAX
 * conditional updates, and MIN/MAX recompute from source when needed).
 */
@SdkPublicApi
public final class ProjectionApplicator {

    private final DynamoDbClient client;
    private final ProjectionSpec projection;
    private final String partitionKeyAttr;
    private final String sortKeyAttr;
    private final String sourceTableName;
    private final SourceGroupScanner sourceGroupScanner;
    private final SourceGroupScanner sourceTableScanner;
    private final ProjectionExecutionMode executionMode;
    private final boolean batchWrites;
    private final ExecutorService batchExecutor;
    private final ProjectionStateStore stateStore;

    private ProjectionApplicator(Builder builder) {
        this.client = Validate.paramNotNull(builder.client, "client");
        this.projection = Validate.paramNotNull(builder.projection, "projection");
        this.partitionKeyAttr = Validate.paramNotBlank(builder.partitionKeyAttr, "partitionKeyAttr");
        this.sortKeyAttr = builder.sortKeyAttr;
        this.sourceTableName = builder.sourceTableName;
        this.sourceGroupScanner = builder.sourceGroupScanner;
        this.executionMode = builder.executionMode == null
                             ? ProjectionExecutionMode.STRICT_KEY_ONLY
                             : builder.executionMode;
        if (builder.sourceTableScanner != null) {
            this.sourceTableScanner = builder.sourceTableScanner;
        } else if (sourceTableName != null) {
            this.sourceTableScanner = new DynamoDbSourceTableScanner(client, sourceTableName);
        } else {
            this.sourceTableScanner = null;
        }
        if (hasMinMax(projection) && projection.groupBy().isEmpty()) {
            if (sourceTableScanner == null) {
                throw new IllegalArgumentException(
                    "sourceTableName or sourceTableScanner is required for empty groupBy MIN/MAX");
            }
        } else if (hasMinMax(projection) && sourceGroupScanner == null) {
            throw new IllegalArgumentException(
                "sourceGroupScanner is required when the projection declares MIN or MAX");
        }
        this.batchWrites = builder.batchWrites;
        this.batchExecutor = builder.batchExecutor;
        this.stateStore = builder.stateStore;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ApplyOutcome applyRecord(NormalizedRecord record) {
        ApplyOutcome outcome = ProjectionApplyEngine.buildApplyRequest(projection, record);
        if (outcome.kind() == ApplyOutcome.Kind.SKIPPED) {
            return outcome;
        }
        ApplyOutcome.Applied applied = (ApplyOutcome.Applied) outcome;
        ApplyOutcome.AppliedPlan plan = applied.plan();
        ApplyOutcome checkpointOutcome = applied;
        if (stateStore != null) {
            ApplyOutcome stateOutcome = stateStore.apply(projection, record, plan);
            if (stateOutcome.kind() == ApplyOutcome.Kind.SKIPPED) {
                ApplyOutcome.Skipped skipped = (ApplyOutcome.Skipped) stateOutcome;
                if (skipped.reason() != ApplyOutcome.SkipReason.ALREADY_APPLIED) {
                    return stateOutcome;
                }
                // A prior transaction may have committed source state and additive shadows before
                // this invocation failed while refreshing a derived field. Run the derived work
                // again: it is idempotent from the current source/summary state.
                checkpointOutcome = stateOutcome;
            }
        } else {
            try {
                updateItem(plan.additiveRequest());
            } catch (ConditionalCheckFailedException e) {
                return ApplyOutcome.skipped(ApplyOutcome.SkipReason.NO_AGGREGATE_FIELD_CHANGED);
            }
        }

        for (Map.Entry<String, ApplyOutcome.ExtremeCandidate> e : plan.extremeCandidates().entrySet()) {
            try {
                updateItem(ProjectionApplyEngine.buildExtremeConditionalRequest(
                    projection, plan.targetKey(), e.getKey(), e.getValue()));
            } catch (ConditionalCheckFailedException ignored) {
                // Not a new extreme — expected.
            }
        }

        if (!plan.recomputeAliases().isEmpty()) {
            Map<String, Object> groupKey = groupKeyFromRecord(record);
            List<Map<String, Object>> items = loadGroupForRecompute(groupKey);
            Map<String, Number> recomputed =
                ProjectionApplyEngine.recomputeExtremes(projection, plan.recomputeAliases(), items);
            Map<String, Number> toSet = new LinkedHashMap<>();
            Set<String> toRemove = new HashSet<>();
            for (String alias : plan.recomputeAliases()) {
                if (recomputed.containsKey(alias)) {
                    toSet.put(alias, recomputed.get(alias));
                } else {
                    toRemove.add(alias);
                }
            }
            if (!toSet.isEmpty()) {
                updateItem(ProjectionApplyEngine.buildSetAttributesRequest(
                    projection, plan.targetKey(), toSet));
            }
            if (!toRemove.isEmpty()) {
                updateItem(ProjectionApplyEngine.buildRemoveAttributesRequest(
                    projection, plan.targetKey(), toRemove));
            }
        }

        if (!plan.avgAliases().isEmpty()) {
            refreshAvgAttributes(plan);
        }

        return checkpointOutcome;
    }

    public ApplyOutcome applyStreamRecord(Record streamRecord) {
        NormalizedRecord normalized = StreamRecordDecoder.decode(
            streamRecord, projection.sourceEntityType(), partitionKeyAttr, sortKeyAttr);
        return applyRecord(normalized);
    }

    /**
     * Applies many records for backfill workloads. When {@link Builder#batchWrites(boolean)} is
     * {@code true} and a {@link Builder#batchExecutor(ExecutorService)} is configured, work is
     * submitted in parallel (aggregate paths use {@code UpdateItem}, not BatchWriteItem).
     */
    /**
     * Writes precomputed summary rows directly via {@code BatchWriteItem} (initial backfill).
     * Each item must include the target table key attributes and aggregate fields.
     */
    public void batchPutPrecomputedItems(List<Map<String, AttributeValue>> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        String tableName = projection.target().tableName();
        ExecutorService executor = batchWrites ? batchExecutor : null;
        ProjectionBatchWriter.batchPutItems(client, tableName, items, executor);
    }

    public List<ApplyOutcome> applyRecords(List<NormalizedRecord> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        if (!batchWrites || batchExecutor == null || records.size() == 1) {
            List<ApplyOutcome> sequential = new ArrayList<>(records.size());
            for (NormalizedRecord record : records) {
                sequential.add(applyRecord(record));
            }
            return sequential;
        }
        List<Future<ApplyOutcome>> futures = new ArrayList<>(records.size());
        for (NormalizedRecord record : records) {
            futures.add(batchExecutor.submit(() -> applyRecord(record)));
        }
        List<ApplyOutcome> results = new ArrayList<>(records.size());
        for (Future<ApplyOutcome> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProjectionException("parallel apply interrupted", e);
            } catch (ExecutionException e) {
                throw new ProjectionException("parallel apply failed", e.getCause());
            }
        }
        return results;
    }

    public void close() {
        ProjectionBatchWriter.shutdownQuietly(batchExecutor);
    }

    public ProjectionSpec projection() {
        return projection;
    }

    public ProjectionExecutionMode executionMode() {
        return executionMode;
    }

    private List<Map<String, Object>> loadGroupForRecompute(Map<String, Object> groupKey) {
        if (projection.groupBy().isEmpty()) {
            requireAllowScan("empty groupBy MIN/MAX recompute requires a full source table Scan");
            return sourceTableScanner.loadGroup(projection, groupKey);
        }
        return sourceGroupScanner.loadGroup(projection, groupKey);
    }

    private void requireAllowScan(String reason) {
        if (executionMode != ProjectionExecutionMode.ALLOW_SCAN) {
            throw new ProjectionExecutionPolicyException(
                "ProjectionExecutionMode.STRICT_KEY_ONLY forbids Scan: " + reason
                + ". Use ProjectionExecutionMode.ALLOW_SCAN to opt in.");
        }
    }

    private void refreshAvgAttributes(ApplyOutcome.AppliedPlan plan) {
        GetItemResponse got = client.getItem(GetItemRequest.builder()
                                                           .tableName(projection.target().tableName())
                                                           .key(plan.targetKey())
                                                           .consistentRead(true)
                                                           .build());
        Map<String, AttributeValue> item = got.item() == null ? Collections.emptyMap() : got.item();
        Map<String, Number> toSet = new LinkedHashMap<>();
        Set<String> toRemove = new HashSet<>();
        for (String alias : plan.avgAliases()) {
            AttributeValue sumAv = item.get(AggregateDefinition.avgSumAttr(alias));
            AttributeValue cntAv = item.get(AggregateDefinition.avgCountAttr(alias));
            BigDecimal sum = sumAv != null && sumAv.n() != null ? new BigDecimal(sumAv.n()) : BigDecimal.ZERO;
            BigDecimal count = cntAv != null && cntAv.n() != null ? new BigDecimal(cntAv.n()) : BigDecimal.ZERO;
            if (count.compareTo(BigDecimal.ZERO) == 0) {
                toRemove.add(alias);
            } else {
                toSet.put(alias, sum.divide(count, MathContext.DECIMAL128));
            }
        }
        if (!toSet.isEmpty()) {
            updateItem(ProjectionApplyEngine.buildSetAttributesRequest(
                projection, plan.targetKey(), toSet));
        }
        if (!toRemove.isEmpty()) {
            updateItem(ProjectionApplyEngine.buildRemoveAttributesRequest(
                projection, plan.targetKey(), toRemove));
        }
    }

    private void updateItem(UpdateItemRequest request) {
        try {
            UpdateItemResponse response = client.updateItem(request);
            ProjectionWriteMetrics.record(response == null ? null : response.consumedCapacity());
        } catch (RuntimeException e) {
            ProjectionWriteMetrics.record(null);
            throw e;
        }
    }

    private Map<String, Object> groupKeyFromRecord(NormalizedRecord record) {
        Map<String, Object> image = record.activeImage();
        Map<String, Object> key = new LinkedHashMap<>();
        for (String field : projection.groupBy()) {
            key.put(field, image.get(field));
        }
        return key;
    }

    private static boolean hasMinMax(ProjectionSpec projection) {
        for (AggregateDefinition def : projection.fields().values()) {
            if (def.aggregationFunction() == AggregateDefinition.AggregationFunction.MIN
                || def.aggregationFunction() == AggregateDefinition.AggregationFunction.MAX) {
                return true;
            }
        }
        return false;
    }

    public static final class Builder {
        private DynamoDbClient client;
        private ProjectionSpec projection;
        private String partitionKeyAttr = "id";
        private String sortKeyAttr;
        private String sourceTableName;
        private SourceGroupScanner sourceGroupScanner;
        private SourceGroupScanner sourceTableScanner;
        private ProjectionExecutionMode executionMode;
        private boolean batchWrites;
        private ExecutorService batchExecutor;
        private ProjectionStateStore stateStore;

        public Builder client(DynamoDbClient client) {
            this.client = client;
            return this;
        }

        public Builder projection(ProjectionSpec projection) {
            this.projection = projection;
            return this;
        }

        public Builder sourcePartitionKey(String partitionKeyAttr) {
            this.partitionKeyAttr = partitionKeyAttr;
            return this;
        }

        public Builder sourceSortKey(String sortKeyAttr) {
            this.sortKeyAttr = sortKeyAttr;
            return this;
        }

        public Builder sourceTableName(String sourceTableName) {
            this.sourceTableName = sourceTableName;
            return this;
        }

        public Builder sourceGroupScanner(SourceGroupScanner sourceGroupScanner) {
            this.sourceGroupScanner = sourceGroupScanner;
            return this;
        }

        public Builder sourceTableScanner(SourceGroupScanner sourceTableScanner) {
            this.sourceTableScanner = sourceTableScanner;
            return this;
        }

        public Builder executionMode(ProjectionExecutionMode executionMode) {
            this.executionMode = executionMode;
            return this;
        }

        public Builder batchWrites(boolean batchWrites) {
            this.batchWrites = batchWrites;
            return this;
        }

        public Builder stateStore(ProjectionStateStore stateStore) {
            this.stateStore = stateStore;
            return this;
        }

        public Builder batchExecutor(ExecutorService batchExecutor) {
            this.batchExecutor = batchExecutor;
            return this;
        }

        public ProjectionApplicator build() {
            return new ProjectionApplicator(this);
        }
    }
}
