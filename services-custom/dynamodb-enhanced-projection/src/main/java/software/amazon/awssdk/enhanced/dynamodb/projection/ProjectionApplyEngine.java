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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.utils.Validate;

/**
 * Builds the apply plan for a projection + stream record (additive UpdateItem, AVG refresh,
 * MIN/MAX conditional updates, and recompute markers).
 */
@SdkPublicApi
public final class ProjectionApplyEngine {

    private ProjectionApplyEngine() {
    }

    public static ApplyOutcome buildApplyRequest(ProjectionSpec projection, NormalizedRecord record) {
        Validate.paramNotNull(projection, "projection");
        Validate.paramNotNull(record, "record");

        if (!Objects.equals(record.entityType(), projection.sourceEntityType())) {
            return ApplyOutcome.skipped(ApplyOutcome.SkipReason.WRONG_ENTITY_TYPE);
        }

        assertImmutableGroupKeys(projection, record);

        Map<String, Number> additiveDeltas = new LinkedHashMap<>();
        Set<String> avgAliases = new LinkedHashSet<>();
        Map<String, ApplyOutcome.ExtremeCandidate> extremeCandidates = new LinkedHashMap<>();
        Set<String> recomputeAliases = new LinkedHashSet<>();
        boolean anyWork = false;

        for (Map.Entry<String, AggregateDefinition> entry : projection.fields().entrySet()) {
            String alias = entry.getKey();
            AggregateDefinition def = entry.getValue();
            switch (def.aggregationFunction()) {
                case COUNT:
                case SUM: {
                    Number delta = AggregateDeltaMath.computeAdditiveDelta(
                        def, record.eventName(), record.prev(), record.next(), null);
                    additiveDeltas.put(alias, delta);
                    if (!AggregateDeltaMath.isZero(delta)) {
                        anyWork = true;
                    }
                    break;
                }
                case AVG: {
                    Number sumDelta = AggregateDeltaMath.computeAdditiveDelta(
                        def, record.eventName(), record.prev(), record.next(),
                        AggregateDeltaMath.AvgShadow.SUM);
                    Number countDelta = AggregateDeltaMath.computeAdditiveDelta(
                        def, record.eventName(), record.prev(), record.next(),
                        AggregateDeltaMath.AvgShadow.COUNT);
                    additiveDeltas.put(AggregateDefinition.avgSumAttr(alias), sumDelta);
                    additiveDeltas.put(AggregateDefinition.avgCountAttr(alias), countDelta);
                    avgAliases.add(alias);
                    if (!AggregateDeltaMath.isZero(sumDelta) || !AggregateDeltaMath.isZero(countDelta)) {
                        anyWork = true;
                    }
                    break;
                }
                case MIN:
                case MAX: {
                    if (AggregateDeltaMath.needsExtremeRecompute(
                        def, record.eventName(), record.prev(), record.next())) {
                        recomputeAliases.add(alias);
                        anyWork = true;
                    } else {
                        java.math.BigDecimal candidate = AggregateDeltaMath.extremeCandidate(
                            def, record.eventName(), record.prev(), record.next());
                        if (candidate != null) {
                            extremeCandidates.put(alias,
                                new ApplyOutcome.ExtremeCandidate(def.aggregationFunction(), candidate));
                            anyWork = true;
                        }
                    }
                    break;
                }
                default:
                    throw new IllegalStateException(
                        "unknown aggregation function: " + def.aggregationFunction());
            }
        }

        if (!anyWork) {
            return ApplyOutcome.skipped(ApplyOutcome.SkipReason.NO_AGGREGATE_FIELD_CHANGED);
        }

        Map<String, AttributeValue> key = composeTargetKey(projection, record);
        String effectiveVersion = effectiveSourceVersion(record);

        // Always touch version map when we do work, even if additive deltas are empty
        // (pure MIN/MAX recompute / conditional path).
        UpdateItemRequest additiveRequest = buildAdditiveRequest(
            projection, key, record.sourceItemKey(), effectiveVersion, additiveDeltas, activeImage(record));

        return ApplyOutcome.applied(ApplyOutcome.AppliedPlan.builder()
                                                            .targetKey(key)
                                                            .sourceItemKey(record.sourceItemKey())
                                                            .effectiveVersion(effectiveVersion)
                                                            .additiveDeltas(additiveDeltas)
                                                            .avgAliases(avgAliases)
                                                            .extremeCandidates(extremeCandidates)
                                                            .recomputeAliases(recomputeAliases)
                                                            .additiveRequest(additiveRequest)
                                                            .build());
    }

    public static String effectiveSourceVersion(NormalizedRecord record) {
        if (record.eventName() == NormalizedRecord.EventName.REMOVE) {
            return record.sourceVersion() + "#REMOVE";
        }
        return record.sourceVersion();
    }

    /**
     * Recompute MIN/MAX for the given aliases from source group items.
     */
    public static Map<String, Number> recomputeExtremes(ProjectionSpec projection,
                                                        Set<String> aliases,
                                                        List<Map<String, Object>> groupItems) {
        Map<String, Number> result = new LinkedHashMap<>();
        for (String alias : aliases) {
            AggregateDefinition def = projection.fields().get(alias);
            if (def == null) {
                continue;
            }
            if (def.aggregationFunction() != AggregateDefinition.AggregationFunction.MIN
                && def.aggregationFunction() != AggregateDefinition.AggregationFunction.MAX) {
                continue;
            }
            java.math.BigDecimal extreme = null;
            for (Map<String, Object> item : groupItems) {
                java.math.BigDecimal v = AggregateDeltaMath.numericContribution(def, item);
                if (v == null) {
                    continue;
                }
                if (extreme == null) {
                    extreme = v;
                } else if (def.aggregationFunction() == AggregateDefinition.AggregationFunction.MIN) {
                    extreme = extreme.min(v);
                } else {
                    extreme = extreme.max(v);
                }
            }
            if (extreme != null) {
                result.put(alias, extreme);
            }
        }
        return result;
    }

    static UpdateItemRequest buildAdditiveRequest(ProjectionSpec projection,
                                                  Map<String, AttributeValue> key,
                                                  String sourceItemKey,
                                                  String effectiveVersion,
                                                  Map<String, Number> additiveDeltas,
                                                  Map<String, Object> sourceImage) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("#ventry", versionMapAttributeName(sourceItemKey));
        names.put("#tver", "_v");
        names.put("#owner", "_owner");

        Map<String, AttributeValue> values = new LinkedHashMap<>();
        values.put(":_v", AttributeValue.builder().s(effectiveVersion).build());
        values.put(":_v_target", AttributeValue.builder().s(VersionGenerator.next()).build());
        values.put(":owner", AttributeValue.builder().s(projection.name()).build());

        StringBuilder update = new StringBuilder();
        if (!additiveDeltas.isEmpty()) {
            update.append("ADD ");
            int index = 0;
            for (Map.Entry<String, Number> entry : additiveDeltas.entrySet()) {
                String namePh = "#agg_" + index;
                String valuePh = ":d_" + index;
                names.put(namePh, entry.getKey());
                values.put(valuePh, AttributeValueMaps.toAttributeValue(entry.getValue()));
                if (index > 0) {
                    update.append(", ");
                }
                update.append(namePh).append(' ').append(valuePh);
                index++;
            }
            update.append(' ');
        }
        update.append("SET #ventry = :_v, #tver = :_v_target, #owner = if_not_exists(#owner, :owner)");
        if (projection.aggregateGsi().isPresent()) {
            AggregateGsi gsi = projection.aggregateGsi().get();
            names.put("#gsipk", gsi.partitionKeyAttribute());
            values.put(":gsipk", AttributeValue.builder().s(gsi.partitionKeyValue()).build());
            update.append(", #gsipk = :gsipk");
        }
        if (sourceImage != null && !projection.carryForwardAttributes().isEmpty()) {
            int cfIndex = 0;
            for (String attr : projection.carryForwardAttributes()) {
                if (projection.groupBy().contains(attr)) {
                    continue;
                }
                Object raw = sourceImage.get(attr);
                if (raw == null) {
                    continue;
                }
                String namePh = "#cf_" + cfIndex;
                String valuePh = ":cf_" + cfIndex;
                names.put(namePh, attr);
                values.put(valuePh, AttributeValueMaps.toAttributeValue(raw));
                update.append(", ").append(namePh).append(" = ").append(valuePh);
                cfIndex++;
            }
        }

        boolean removeEvent = effectiveVersion.endsWith("#REMOVE");
        String versionCondition = removeEvent
                                  ? "attribute_exists(#ventry) AND #ventry < :_v"
                                  : "attribute_not_exists(#ventry) OR #ventry < :_v";
        String ownerCondition = "attribute_not_exists(#owner) OR #owner = :owner";

        return UpdateItemRequest.builder()
                                .tableName(projection.target().tableName())
                                .key(key)
                                .updateExpression(update.toString())
                                .conditionExpression("(" + ownerCondition + ") AND (" + versionCondition + ")")
                                .expressionAttributeNames(names)
                                .expressionAttributeValues(values)
                                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                                .build();
    }

    static UpdateItemRequest buildExtremeConditionalRequest(ProjectionSpec projection,
                                                            Map<String, AttributeValue> key,
                                                            String alias,
                                                            ApplyOutcome.ExtremeCandidate candidate) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("#ext", alias);
        names.put("#owner", "_owner");
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        values.put(":v", AttributeValueMaps.toAttributeValue(candidate.value()));
        values.put(":owner", AttributeValue.builder().s(projection.name()).build());

        String compare = candidate.aggregationFunction() == AggregateDefinition.AggregationFunction.MIN
                         ? "attribute_not_exists(#ext) OR #ext > :v"
                         : "attribute_not_exists(#ext) OR #ext < :v";
        String ownerCondition = "attribute_not_exists(#owner) OR #owner = :owner";

        return UpdateItemRequest.builder()
                                .tableName(projection.target().tableName())
                                .key(key)
                                .updateExpression("SET #ext = :v")
                                .conditionExpression("(" + ownerCondition + ") AND (" + compare + ")")
                                .expressionAttributeNames(names)
                                .expressionAttributeValues(values)
                                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                                .build();
    }

    static UpdateItemRequest buildSetAttributesRequest(ProjectionSpec projection,
                                                       Map<String, AttributeValue> key,
                                                       Map<String, Number> attributes) {
        if (attributes.isEmpty()) {
            throw new IllegalArgumentException("attributes must not be empty");
        }
        Map<String, String> names = new LinkedHashMap<>();
        names.put("#owner", "_owner");
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        values.put(":owner", AttributeValue.builder().s(projection.name()).build());
        StringBuilder set = new StringBuilder("SET ");
        int i = 0;
        for (Map.Entry<String, Number> e : attributes.entrySet()) {
            String np = "#a" + i;
            String vp = ":a" + i;
            names.put(np, e.getKey());
            values.put(vp, AttributeValueMaps.toAttributeValue(e.getValue()));
            if (i > 0) {
                set.append(", ");
            }
            set.append(np).append(" = ").append(vp);
            i++;
        }
        return UpdateItemRequest.builder()
                                .tableName(projection.target().tableName())
                                .key(key)
                                .updateExpression(set.toString())
                                .conditionExpression("attribute_not_exists(#owner) OR #owner = :owner")
                                .expressionAttributeNames(names)
                                .expressionAttributeValues(values)
                                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                                .build();
    }

    static UpdateItemRequest buildRemoveAttributesRequest(ProjectionSpec projection,
                                                          Map<String, AttributeValue> key,
                                                          Set<String> attributes) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("#owner", "_owner");
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        values.put(":owner", AttributeValue.builder().s(projection.name()).build());
        StringBuilder remove = new StringBuilder("REMOVE ");
        int i = 0;
        for (String attr : attributes) {
            String np = "#r" + i;
            names.put(np, attr);
            if (i > 0) {
                remove.append(", ");
            }
            remove.append(np);
            i++;
        }
        return UpdateItemRequest.builder()
                                .tableName(projection.target().tableName())
                                .key(key)
                                .updateExpression(remove.toString())
                                .conditionExpression("attribute_not_exists(#owner) OR #owner = :owner")
                                .expressionAttributeNames(names)
                                .expressionAttributeValues(values)
                                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                                .build();
    }

    private static void assertImmutableGroupKeys(ProjectionSpec projection, NormalizedRecord record) {
        if (record.eventName() != NormalizedRecord.EventName.MODIFY
            || record.prev() == null
            || record.next() == null
            || projection.groupBy().isEmpty()) {
            return;
        }
        java.util.List<String> changed = new java.util.ArrayList<>();
        Map<String, Object> prevKey = new LinkedHashMap<>();
        Map<String, Object> nextKey = new LinkedHashMap<>();
        for (String field : projection.groupBy()) {
            Object prevVal = record.prev().get(field);
            Object nextVal = record.next().get(field);
            prevKey.put(field, prevVal);
            nextKey.put(field, nextVal);
            if (!Objects.equals(prevVal, nextVal)) {
                changed.add(field);
            }
        }
        if (!changed.isEmpty()) {
            throw new GroupKeyMutationException(projection.name(), changed, prevKey, nextKey);
        }
    }

    static Map<String, AttributeValue> composeTargetKey(ProjectionSpec projection, NormalizedRecord record) {
        Map<String, Object> row = record.activeImage();
        if (row == null) {
            throw new ProjectionException("normalized record has neither prev nor next");
        }
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        String pk = projection.target().partitionKey();
        if (projection.groupBy().isEmpty()) {
            key.put(pk, AttributeValue.builder().s("ALL").build());
        } else {
            Object pkValue = row.get(projection.groupBy().get(0));
            assertGroupByValue(projection.name(), projection.groupBy().get(0), pkValue);
            key.put(pk, AttributeValueMaps.toAttributeValue(pkValue));
        }
        String sk = projection.target().sortKey();
        if (sk != null) {
            if (projection.groupBy().size() < 2) {
                throw new ProjectionException(
                    "projection \"" + projection.name() + "\": sortKey requires groupBy size >= 2");
            }
            if (projection.groupBy().size() == 2) {
                Object skValue = row.get(projection.groupBy().get(1));
                assertGroupByValue(projection.name(), projection.groupBy().get(1), skValue);
                key.put(sk, AttributeValueMaps.toAttributeValue(skValue));
            } else {
                StringBuilder parts = new StringBuilder();
                for (int i = 1; i < projection.groupBy().size(); i++) {
                    if (i > 1) {
                        parts.append('#');
                    }
                    Object component = row.get(projection.groupBy().get(i));
                    assertGroupByValue(projection.name(), projection.groupBy().get(i), component);
                    parts.append(escapeKeyComponent(String.valueOf(component)));
                }
                key.put(sk, AttributeValue.builder().s(parts.toString()).build());
            }
        }
        return key;
    }

    /**
     * Encodes a source item key for use in a flat version attribute name under the {@code v.} prefix.
     * Raw keys such as {@code c1#o1} are not valid DynamoDB attribute names.
     */
    static String versionMapKey(String sourceItemKey) {
        Validate.paramNotBlank(sourceItemKey, "sourceItemKey");
        return Base64.getUrlEncoder().withoutPadding()
                     .encodeToString(sourceItemKey.getBytes(StandardCharsets.UTF_8));
    }

    static String versionMapAttributeName(String sourceItemKey) {
        return "v." + versionMapKey(sourceItemKey);
    }

    private static void assertGroupByValue(String projection, String field, Object value) {
        if (value == null) {
            throw new ProjectionException(
                "projection \"" + projection + "\": groupBy field \"" + field + "\" is null");
        }
        if (value instanceof String && ((String) value).isEmpty()) {
            throw new ProjectionException(
                "projection \"" + projection + "\": groupBy field \"" + field + "\" is empty");
        }
    }

    private static String escapeKeyComponent(String value) {
        return value.replace("\\", "\\\\").replace("#", "\\#");
    }

    private static Map<String, Object> activeImage(NormalizedRecord record) {
        if (record.eventName() == NormalizedRecord.EventName.REMOVE) {
            return record.prev();
        }
        return record.next();
    }
}
