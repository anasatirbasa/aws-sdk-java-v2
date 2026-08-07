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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.utils.Validate;

/**
 * In-memory projection harness (no DynamoDB). Supports COUNT/SUM/AVG/MIN/MAX including MIN/MAX recompute from an in-memory source
 * snapshot, plus read-time HAVING/ORDER BY pagination via {@link #query(SummaryQuery)}. Defaults to
 * {@link ProjectionExecutionMode#ALLOW_SCAN} so SummaryQuery works in tests.
 */
@SdkPublicApi
public final class ProjectionHarness {

    /**
     * Cap per-source-item version entries to bound summary row size.
     */
    static final int MAX_VERSION_MAP_ENTRIES = 10_000;

    private final ProjectionSpec projection;
    private final ProjectionExecutionMode executionMode;
    private final Map<String, TargetRow> targets = new ConcurrentHashMap<>();
    /**
     * groupKeyId → (sourceItemKey → item image) for MIN/MAX recompute.
     */
    private final Map<String, Map<String, Map<String, Object>>> sourceByGroup = new ConcurrentHashMap<>();
    /**
     * Serializes updates to the single global summary row when groupBy is empty.
     */
    private final Object globalRowLock = new Object();

    public ProjectionHarness(ProjectionSpec projection) {
        this(projection, ProjectionExecutionMode.ALLOW_SCAN);
    }

    public ProjectionHarness(ProjectionSpec projection, ProjectionExecutionMode executionMode) {
        this.projection = Validate.paramNotNull(projection, "projection");
        this.executionMode = executionMode == null
                             ? ProjectionExecutionMode.ALLOW_SCAN
                             : executionMode;
    }

    public static ProjectionHarness of(ProjectionSpec projection) {
        return new ProjectionHarness(projection);
    }

    public static ProjectionHarness of(ProjectionSpec projection, ProjectionExecutionMode mode) {
        return new ProjectionHarness(projection, mode);
    }

    public ProjectionExecutionMode executionMode() {
        return executionMode;
    }

    public ApplyOutcome applyRecord(NormalizedRecord record) {
        ApplyOutcome outcome = ProjectionApplyEngine.buildApplyRequest(projection, record);
        if (outcome.kind() == ApplyOutcome.Kind.SKIPPED) {
            return outcome;
        }
        ApplyOutcome.Applied applied = (ApplyOutcome.Applied) outcome;
        ApplyOutcome.AppliedPlan plan = applied.plan();
        if (projection.groupBy().isEmpty()) {
            synchronized (globalRowLock) {
                return applyPlan(record, applied, plan);
            }
        }
        return applyPlan(record, applied, plan);
    }

    private ApplyOutcome applyPlan(NormalizedRecord record, ApplyOutcome.Applied applied,
                                   ApplyOutcome.AppliedPlan plan) {
        String keyId = keyId(plan.targetKey());
        TargetRow row = targets.computeIfAbsent(keyId, k -> new TargetRow(plan.targetKey()));

        String effectiveVersion = plan.effectiveVersion();
        String existing = row.versionMap.get(record.sourceItemKey());
        if (record.eventName() == NormalizedRecord.EventName.REMOVE) {
            if (existing == null || existing.compareTo(effectiveVersion) >= 0) {
                return ApplyOutcome.skipped(ApplyOutcome.SkipReason.NO_AGGREGATE_FIELD_CHANGED);
            }
        } else if (existing != null && existing.compareTo(effectiveVersion) >= 0) {
            return ApplyOutcome.skipped(ApplyOutcome.SkipReason.NO_AGGREGATE_FIELD_CHANGED);
        }

        if (row.owner != null && !Objects.equals(row.owner, projection.name())) {
            throw new ProjectionException("owner conflict on target row " + keyId);
        }
        row.owner = projection.name();
        row.versionMap.put(record.sourceItemKey(), effectiveVersion);
        pruneVersionMap(row);

        updateSourceSnapshot(keyId, record);

        applyCarryForward(row, record);

        for (Map.Entry<String, Number> delta : plan.additiveDeltas().entrySet()) {
            BigDecimal current = row.aggregates.getOrDefault(delta.getKey(), BigDecimal.ZERO);
            row.aggregates.put(delta.getKey(), current.add(toBigDecimal(delta.getValue())));
        }

        refreshAvgs(row, plan.avgAliases());

        projection.aggregateGsi().ifPresent(gsi ->
                                                row.gsiAttrs.put(gsi.partitionKeyAttribute(), gsi.partitionKeyValue()));

        for (Map.Entry<String, ApplyOutcome.ExtremeCandidate> e : plan.extremeCandidates().entrySet()) {
            applyExtremeCandidate(row, e.getKey(), e.getValue());
        }

        if (!plan.recomputeAliases().isEmpty()) {
            List<Map<String, Object>> items = currentGroupItems(keyId);
            Map<String, Number> recomputed =
                ProjectionApplyEngine.recomputeExtremes(projection, plan.recomputeAliases(), items);
            for (String alias : plan.recomputeAliases()) {
                if (recomputed.containsKey(alias)) {
                    row.aggregates.put(alias, toBigDecimal(recomputed.get(alias)));
                } else {
                    row.aggregates.remove(alias);
                }
            }
        }

        return applied;
    }

    public Map<String, Number> getAggregates(Map<String, Object> targetKey) {
        TargetRow row = targets.get(keyId(AttributeValueMaps.toAttributeMap(targetKey)));
        if (row == null) {
            return Collections.emptyMap();
        }
        Map<String, Number> visible = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : row.aggregates.entrySet()) {
            if (!e.getKey().startsWith("_avg_")) {
                visible.put(e.getKey(), e.getValue());
            }
        }
        return Collections.unmodifiableMap(visible);
    }

    public Double storedAvg(Map<String, Object> targetKey, String avgAlias) {
        Number n = getAggregates(targetKey).get(avgAlias);
        return n == null ? null : n.doubleValue();
    }

    /**
     * Client-side average from existing SUM and COUNT fields (not a stored {@code AVG} aggregate).
     */
    public Double derivedAvg(Map<String, Object> targetKey, String sumAlias, String countAlias) {
        Map<String, Number> aggs = getAggregates(targetKey);
        Number sum = aggs.get(sumAlias);
        Number count = aggs.get(countAlias);
        if (sum == null || count == null || count.doubleValue() == 0) {
            return null;
        }
        return sum.doubleValue() / count.doubleValue();
    }

    /**
     * All current summary rows (group key + visible aggregates).
     */
    public List<SummaryRow> listSummaryRows() {
        List<SummaryRow> rows = new ArrayList<>();
        for (TargetRow row : targets.values()) {
            Map<String, Object> key = AttributeValueMaps.fromAttributeMap(row.key);
            Map<String, Number> aggs = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> e : row.aggregates.entrySet()) {
                if (!e.getKey().startsWith("_avg_")) {
                    aggs.put(e.getKey(), e.getValue());
                }
            }
            rows.add(new SummaryRow(key, aggs, AttributeValueMaps.fromAttributeMap(row.carryForward)));
        }
        return Collections.unmodifiableList(rows);
    }

    /**
     * Read-time HAVING / ORDER BY / limit / cursor over summary rows (same role as Enhanced Queries post-aggregation
     * filter/sort).
     */
    public SummaryPage query(SummaryQuery query) {
        if (executionMode != ProjectionExecutionMode.ALLOW_SCAN) {
            throw new ProjectionExecutionPolicyException(
                "ProjectionExecutionMode.STRICT_KEY_ONLY forbids SummaryQuery over all summary rows. "
                + "Use ProjectionExecutionMode.ALLOW_SCAN to opt in.");
        }
        return SummaryQueryEngine.execute(listSummaryRows(), query);
    }

    /**
     * GSI partition-key attribute written on apply when {@link AggregateGsi} is declared.
     */
    public String gsiAttribute(Map<String, Object> targetKey, String attributeName) {
        TargetRow row = targets.get(keyId(AttributeValueMaps.toAttributeMap(targetKey)));
        if (row == null) {
            return null;
        }
        return row.gsiAttrs.get(attributeName);
    }

    public ProjectionSpec projection() {
        return projection;
    }

    /**
     * Loads a precomputed summary row (benchmark bulk seed). Skips version-map / stream simulation.
     */
    public void loadPrecomputedSummary(Map<String, AttributeValue> key,
                                       Map<String, Number> aggregates,
                                       Map<String, AttributeValue> carryForwardAttributes) {
        Validate.paramNotNull(key, "key");
        Validate.paramNotNull(aggregates, "aggregates");
        TargetRow row = targets.computeIfAbsent(keyId(key), k -> new TargetRow(key));
        row.owner = projection.name();
        row.aggregates.clear();
        for (Map.Entry<String, Number> entry : aggregates.entrySet()) {
            row.aggregates.put(entry.getKey(), toBigDecimal(entry.getValue()));
        }
        if (aggregates.containsKey("avgAmount")
            && aggregates.containsKey("totalAmount")
            && aggregates.containsKey("orderCount")) {
            row.aggregates.put(AggregateDefinition.avgSumAttr("avgAmount"),
                               toBigDecimal(aggregates.get("totalAmount")));
            row.aggregates.put(AggregateDefinition.avgCountAttr("avgAmount"),
                               toBigDecimal(aggregates.get("orderCount")));
        }
        row.carryForward.clear();
        if (carryForwardAttributes != null) {
            row.carryForward.putAll(carryForwardAttributes);
        }
        projection.aggregateGsi().ifPresent(gsi ->
                                                  row.gsiAttrs.put(gsi.partitionKeyAttribute(),
                                                                   gsi.partitionKeyValue()));
    }

    private void updateSourceSnapshot(String keyId, NormalizedRecord record) {
        Map<String, Map<String, Object>> group =
            sourceByGroup.computeIfAbsent(keyId, k -> new ConcurrentHashMap<>());
        if (record.eventName() == NormalizedRecord.EventName.REMOVE) {
            group.remove(record.sourceItemKey());
        } else if (record.next() != null) {
            group.put(record.sourceItemKey(), new LinkedHashMap<>(record.next()));
        }
    }

    private List<Map<String, Object>> currentGroupItems(String keyId) {
        Map<String, Map<String, Object>> group = sourceByGroup.get(keyId);
        if (group == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(group.values());
    }

    private void refreshAvgs(TargetRow row, Set<String> avgAliases) {
        for (String alias : avgAliases) {
            BigDecimal sum = row.aggregates.getOrDefault(
                AggregateDefinition.avgSumAttr(alias), BigDecimal.ZERO);
            BigDecimal count = row.aggregates.getOrDefault(
                AggregateDefinition.avgCountAttr(alias), BigDecimal.ZERO);
            if (count.compareTo(BigDecimal.ZERO) == 0) {
                row.aggregates.remove(alias);
            } else {
                row.aggregates.put(alias, sum.divide(count, 12, RoundingMode.HALF_UP));
            }
        }
    }

    private static void applyExtremeCandidate(TargetRow row,
                                              String alias,
                                              ApplyOutcome.ExtremeCandidate candidate) {
        BigDecimal incoming = toBigDecimal(candidate.value());
        BigDecimal current = row.aggregates.get(alias);
        if (current == null) {
            row.aggregates.put(alias, incoming);
            return;
        }
        if (candidate.aggregationFunction() == AggregateDefinition.AggregationFunction.MIN
            && incoming.compareTo(current) < 0) {
            row.aggregates.put(alias, incoming);
        } else if (candidate.aggregationFunction() == AggregateDefinition.AggregationFunction.MAX
                   && incoming.compareTo(current) > 0) {
            row.aggregates.put(alias, incoming);
        }
    }

    private static String keyId(Map<String, AttributeValue> key) {
        StringBuilder sb = new StringBuilder();
        key.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            sb.append(e.getKey()).append('=');
            AttributeValue v = e.getValue();
            sb.append(v.s() != null ? v.s() : v.n()).append(';');
        });
        return sb.toString();
    }

    private static BigDecimal toBigDecimal(Number n) {
        if (n instanceof BigDecimal) {
            return (BigDecimal) n;
        }
        return new BigDecimal(n.toString());
    }

    private static void pruneVersionMap(TargetRow row) {
        while (row.versionMap.size() > MAX_VERSION_MAP_ENTRIES) {
            String oldest = row.versionMap.keySet().iterator().next();
            row.versionMap.remove(oldest);
        }
    }

    private void applyCarryForward(TargetRow row, NormalizedRecord record) {
        if (projection.carryForwardAttributes().isEmpty()) {
            return;
        }
        Map<String, Object> image = record.eventName() == NormalizedRecord.EventName.REMOVE
                                    ? record.prev() : record.next();
        if (image == null) {
            return;
        }
        for (String attr : projection.carryForwardAttributes()) {
            if (projection.groupBy().contains(attr)) {
                continue;
            }
            Object value = image.get(attr);
            if (value != null) {
                row.carryForward.put(attr, AttributeValueMaps.toAttributeValue(value));
            }
        }
    }

    private static final class TargetRow {
        private final Map<String, AttributeValue> key;
        private final Map<String, BigDecimal> aggregates = new LinkedHashMap<>();
        private final Map<String, String> gsiAttrs = new LinkedHashMap<>();
        private final Map<String, String> versionMap = new LinkedHashMap<>();
        private final Map<String, AttributeValue> carryForward = new LinkedHashMap<>();
        private String owner;

        private TargetRow(Map<String, AttributeValue> key) {
            this.key = key;
        }
    }
}
