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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.utils.Validate;

/**
 * Plans DynamoDB writes for a {@link JoinProjectionSpec} given a stream record.
 *
 * <p>Right-side (child) events upsert or delete a single join row (and may remove/restore a
 * left-only sentinel for {@link JoinType#LEFT}/{@link JoinType#FULL}). Left-side (parent)
 * events fan out to existing join rows supplied by the caller (from a Query), or maintain a
 * left-only sentinel when there are no children.
 */
@SdkPublicApi
public final class JoinProjectionApplyEngine {

    /**
     * Sort-key value used for parent-only rows under {@link JoinType#LEFT} / {@link JoinType#FULL}.
     * Must not collide with real right-side sort key values.
     */
    public static final String LEFT_ONLY_SORT_KEY = "__LEFT_ONLY__";

    private JoinProjectionApplyEngine() {
    }

    /**
     * Whether sibling join-row keys must be loaded before planning this record.
     * Returns {@code false} for INNER right-side INSERT where no sibling fan-out is required.
     */
    public static boolean needsSiblingKeys(JoinProjectionSpec projection, NormalizedRecord record) {
        Validate.paramNotNull(projection, "projection");
        Validate.paramNotNull(record, "record");
        if (!Objects.equals(record.entityType(), projection.rightEntityType())) {
            return true;
        }
        if (projection.joinType() != JoinType.INNER) {
            return true;
        }
        if (record.eventName() != NormalizedRecord.EventName.INSERT) {
            return true;
        }
        return false;
    }

    /**
     * Builds a synthetic parent map from embedded left-field values on a child image.
     */
    static Map<String, Object> extractEmbeddedParent(Map<String, Object> child, List<String> leftFields) {
        if (child == null || leftFields == null || leftFields.isEmpty()) {
            return null;
        }
        Map<String, Object> parent = new LinkedHashMap<>();
        for (String field : leftFields) {
            Object v = child.get(field);
            if (v != null) {
                parent.put(field, v);
            }
        }
        return parent.isEmpty() ? null : parent;
    }

    static boolean hasRequiredLeftFields(Map<String, Object> parent, List<String> leftFields) {
        for (String field : leftFields) {
            if (parent.get(field) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     *
     * @param projection join projection
     * @param record normalized stream record
     * @param parentResolver used for right-side events to load parent attributes
     * @param existingJoinKeys join-row keys already materialised for this join-key value
     *                         (siblings for right-side events; all parent rows for left-side)
     */
    public static JoinApplyOutcome plan(JoinProjectionSpec projection,
                                        NormalizedRecord record,
                                        ParentResolver parentResolver,
                                        List<Map<String, AttributeValue>> existingJoinKeys) {
        Validate.paramNotNull(projection, "projection");
        Validate.paramNotNull(record, "record");
        List<Map<String, AttributeValue>> keys =
            existingJoinKeys == null ? new ArrayList<>() : existingJoinKeys;

        if (Objects.equals(record.entityType(), projection.rightEntityType())) {
            return planRight(projection, record, parentResolver, keys);
        }
        if (Objects.equals(record.entityType(), projection.leftEntityType())) {
            return planLeft(projection, record, keys);
        }
        return JoinApplyOutcome.skipped(ApplyOutcome.SkipReason.WRONG_ENTITY_TYPE);
    }

    private static JoinApplyOutcome planRight(JoinProjectionSpec projection,
                                              NormalizedRecord record,
                                              ParentResolver parentResolver,
                                              List<Map<String, AttributeValue>> siblingKeys) {
        Validate.paramNotNull(parentResolver, "parentResolver");
        JoinType type = projection.joinType();

        if (record.eventName() == NormalizedRecord.EventName.REMOVE) {
            Map<String, Object> prev = record.prev();
            if (prev == null) {
                return JoinApplyOutcome.skipped(ApplyOutcome.SkipReason.NO_AGGREGATE_FIELD_CHANGED);
            }
            Map<String, AttributeValue> key = composeJoinKey(projection, prev);
            List<JoinApplyOutcome.Write> writes = new ArrayList<>();
            writes.add(JoinApplyOutcome.Write.delete(DeleteItemRequest.builder()
                                                                      .tableName(projection.target().tableName())
                                                                      .key(key)
                                                                      .build()));

            if (keepsLeftOnlyRows(type)) {
                Object joinKeyVal = prev.get(projection.rightJoinAttribute());
                Map<String, Object> parent = joinKeyVal == null
                                             ? null
                                             : parentResolver.findParent(String.valueOf(joinKeyVal));
                if (parent != null && !hasOtherChildRows(siblingKeys, key, projection)) {
                    writes.add(putLeftOnly(projection, parent, record.sourceVersion()));
                }
            }
            return JoinApplyOutcome.writes(writes);
        }

        Map<String, Object> child = record.next();
        if (child == null) {
            return JoinApplyOutcome.skipped(ApplyOutcome.SkipReason.NO_AGGREGATE_FIELD_CHANGED);
        }
        Object joinKeyVal = child.get(projection.rightJoinAttribute());
        if (joinKeyVal == null || String.valueOf(joinKeyVal).isEmpty()) {
            throw new ProjectionException("join projection \"" + projection.name()
                                          + "\": right join attribute is null/empty");
        }
        Map<String, Object> parent = extractEmbeddedParent(child, projection.leftFields());
        if (parent == null || !hasRequiredLeftFields(parent, projection.leftFields())) {
            parent = parentResolver.findParent(String.valueOf(joinKeyVal));
        }
        if (parent == null) {
            if (type == JoinType.INNER || type == JoinType.LEFT) {
                return JoinApplyOutcome.skipped(ApplyOutcome.SkipReason.MISSING_PARENT);
            }
            // RIGHT / FULL: materialise orphan child with empty left attrs.
        }

        Map<String, AttributeValue> item = buildJoinItem(projection, parent, child, record);
        List<JoinApplyOutcome.Write> writes = new ArrayList<>();
        writes.add(JoinApplyOutcome.Write.put(PutItemRequest.builder()
                                                            .tableName(projection.target().tableName())
                                                            .item(item)
                                                            .build()));
        if (keepsLeftOnlyRows(type) && parent != null) {
            for (Map<String, AttributeValue> sibling : siblingKeys) {
                if (isLeftOnlyKey(sibling, projection)) {
                    writes.add(JoinApplyOutcome.Write.delete(DeleteItemRequest.builder()
                                                                              .tableName(projection.target().tableName())
                                                                              .key(sibling)
                                                                              .build()));
                }
            }
        }
        return JoinApplyOutcome.writes(writes);
    }

    private static JoinApplyOutcome planLeft(JoinProjectionSpec projection,
                                             NormalizedRecord record,
                                             List<Map<String, AttributeValue>> existingJoinKeys) {
        JoinType type = projection.joinType();
        List<Map<String, AttributeValue>> childKeys = childKeysOnly(existingJoinKeys, projection);
        List<Map<String, AttributeValue>> leftOnlyKeys = leftOnlyKeysOnly(existingJoinKeys, projection);

        if (record.eventName() == NormalizedRecord.EventName.REMOVE) {
            return planLeftRemove(projection, type, childKeys, leftOnlyKeys, record.sourceVersion());
        }

        Map<String, Object> parent = record.activeImage();
        if (parent == null) {
            return JoinApplyOutcome.skipped(ApplyOutcome.SkipReason.NO_AGGREGATE_FIELD_CHANGED);
        }

        if (childKeys.isEmpty()) {
            if (!keepsLeftOnlyRows(type)) {
                // INNER / RIGHT: parent-only is a no-op.
                return JoinApplyOutcome.skipped(ApplyOutcome.SkipReason.NO_AGGREGATE_FIELD_CHANGED);
            }
            List<JoinApplyOutcome.Write> writes = new ArrayList<>();
            writes.add(putLeftOnly(projection, parent, record.sourceVersion()));
            return JoinApplyOutcome.writes(writes);
        }

        // Has children: fan-out left attrs; drop any stray left-only sentinel.
        List<JoinApplyOutcome.Write> writes = new ArrayList<>();
        for (Map<String, AttributeValue> key : leftOnlyKeys) {
            writes.add(JoinApplyOutcome.Write.delete(DeleteItemRequest.builder()
                                                                      .tableName(projection.target().tableName())
                                                                      .key(key)
                                                                      .build()));
        }
        writes.addAll(fanOutLeftUpdates(projection, parent, childKeys, record.sourceVersion()));
        return JoinApplyOutcome.writes(writes);
    }

    private static JoinApplyOutcome planLeftRemove(JoinProjectionSpec projection,
                                                   JoinType type,
                                                   List<Map<String, AttributeValue>> childKeys,
                                                   List<Map<String, AttributeValue>> leftOnlyKeys,
                                                   String sourceVersion) {
        if (childKeys.isEmpty() && leftOnlyKeys.isEmpty()) {
            return JoinApplyOutcome.skipped(ApplyOutcome.SkipReason.NO_AGGREGATE_FIELD_CHANGED);
        }

        List<JoinApplyOutcome.Write> writes = new ArrayList<>();
        if (type == JoinType.INNER || type == JoinType.LEFT) {
            for (Map<String, AttributeValue> key : childKeys) {
                writes.add(deleteKey(projection, key));
            }
            for (Map<String, AttributeValue> key : leftOnlyKeys) {
                writes.add(deleteKey(projection, key));
            }
            return JoinApplyOutcome.writes(writes);
        }

        // RIGHT / FULL: keep child rows but clear denormalised left attrs; drop left-only.
        for (Map<String, AttributeValue> key : leftOnlyKeys) {
            writes.add(deleteKey(projection, key));
        }
        for (Map<String, AttributeValue> key : childKeys) {
            writes.add(JoinApplyOutcome.Write.update(clearLeftAttrsUpdate(projection, key, sourceVersion)));
        }
        return JoinApplyOutcome.writes(writes);
    }

    private static List<JoinApplyOutcome.Write> fanOutLeftUpdates(JoinProjectionSpec projection,
                                                                  Map<String, Object> parent,
                                                                  List<Map<String, AttributeValue>> childKeys,
                                                                  String sourceVersion) {
        Map<String, AttributeValue> leftAttrs = projectLeftAttrs(projection, parent);
        leftAttrs.put("_owner", AttributeValue.builder().s(projection.name()).build());
        leftAttrs.put("_leftV", AttributeValue.builder().s(sourceVersion).build());

        List<JoinApplyOutcome.Write> writes = new ArrayList<>();
        for (Map<String, AttributeValue> key : childKeys) {
            Map<String, String> names = new LinkedHashMap<>();
            Map<String, AttributeValue> values = new LinkedHashMap<>();
            StringBuilder set = new StringBuilder("SET ");
            int i = 0;
            for (Map.Entry<String, AttributeValue> e : leftAttrs.entrySet()) {
                String n = "#lf" + i;
                String v = ":lv" + i;
                if (i > 0) {
                    set.append(", ");
                }
                names.put(n, e.getKey());
                values.put(v, e.getValue());
                set.append(n).append(" = ").append(v);
                i++;
            }
            UpdateItemRequest update = UpdateItemRequest.builder()
                                                        .tableName(projection.target().tableName())
                                                        .key(key)
                                                        .updateExpression(set.toString())
                                                        .expressionAttributeNames(names)
                                                        .expressionAttributeValues(values)
                                                        .build();
            writes.add(JoinApplyOutcome.Write.update(update));
        }
        return writes;
    }

    private static UpdateItemRequest clearLeftAttrsUpdate(JoinProjectionSpec projection,
                                                          Map<String, AttributeValue> key,
                                                          String sourceVersion) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        StringBuilder remove = new StringBuilder();
        int i = 0;
        for (String field : projection.leftFields()) {
            String n = "#rm" + i;
            if (i > 0) {
                remove.append(", ");
            }
            names.put(n, field);
            remove.append(n);
            i++;
        }
        names.put("#ov", "_owner");
        names.put("#vv", "_leftV");
        values.put(":ov", AttributeValue.builder().s(projection.name()).build());
        values.put(":vv", AttributeValue.builder().s(sourceVersion).build());

        StringBuilder expr = new StringBuilder();
        if (remove.length() > 0) {
            expr.append("REMOVE ").append(remove).append(" ");
        }
        expr.append("SET #ov = :ov, #vv = :vv");

        return UpdateItemRequest.builder()
                                .tableName(projection.target().tableName())
                                .key(key)
                                .updateExpression(expr.toString().trim())
                                .expressionAttributeNames(names)
                                .expressionAttributeValues(values)
                                .build();
    }

    private static JoinApplyOutcome.Write deleteKey(JoinProjectionSpec projection,
                                                    Map<String, AttributeValue> key) {
        return JoinApplyOutcome.Write.delete(DeleteItemRequest.builder()
                                                              .tableName(projection.target().tableName())
                                                              .key(key)
                                                              .build());
    }

    private static JoinApplyOutcome.Write putLeftOnly(JoinProjectionSpec projection,
                                                      Map<String, Object> parent,
                                                      String version) {
        return JoinApplyOutcome.Write.put(PutItemRequest.builder()
                                                        .tableName(projection.target().tableName())
                                                        .item(buildLeftOnlyItem(projection, parent, version))
                                                        .build());
    }

    static Map<String, AttributeValue> buildLeftOnlyItem(JoinProjectionSpec projection,
                                                         Map<String, Object> parent,
                                                         String version) {
        Object joinVal = parent.get(projection.leftJoinAttribute());
        if (joinVal == null) {
            throw new ProjectionException("join projection \"" + projection.name()
                                          + "\": missing left join attribute on parent image");
        }
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put(projection.target().partitionKey(), AttributeValueMaps.toAttributeValue(joinVal));
        item.put(projection.target().sortKey(), AttributeValue.builder().s(LEFT_ONLY_SORT_KEY).build());
        item.putAll(projectLeftAttrs(projection, parent));
        item.put("_owner", AttributeValue.builder().s(projection.name()).build());
        item.put("_v", AttributeValue.builder().s(version).build());
        item.put("_leftOnly", AttributeValue.builder().bool(true).build());
        return item;
    }

    static Map<String, AttributeValue> buildJoinItem(JoinProjectionSpec projection,
                                                     Map<String, Object> parent,
                                                     Map<String, Object> child,
                                                     NormalizedRecord record) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.putAll(composeJoinKey(projection, child));
        if (parent != null) {
            item.putAll(projectLeftAttrs(projection, parent));
        }
        for (String field : projection.rightFields()) {
            Object v = child.get(field);
            if (v != null) {
                item.put(field, AttributeValueMaps.toAttributeValue(v));
            }
        }
        // Always include join + sort key attrs from child even if not listed in rightFields.
        Object joinVal = child.get(projection.rightJoinAttribute());
        Object skVal = child.get(projection.rightSortKeyAttribute());
        item.put(projection.target().partitionKey(), AttributeValueMaps.toAttributeValue(joinVal));
        item.put(projection.target().sortKey(), AttributeValueMaps.toAttributeValue(skVal));
        item.put("_owner", AttributeValue.builder().s(projection.name()).build());
        item.put("_v", AttributeValue.builder().s(record.sourceVersion()).build());
        item.put("_rightKey", AttributeValue.builder().s(record.sourceItemKey()).build());
        return item;
    }

    static Map<String, AttributeValue> composeJoinKey(JoinProjectionSpec projection,
                                                      Map<String, Object> childOrPrev) {
        Object joinVal = childOrPrev.get(projection.rightJoinAttribute());
        Object skVal = childOrPrev.get(projection.rightSortKeyAttribute());
        if (joinVal == null || skVal == null) {
            throw new ProjectionException("join projection \"" + projection.name()
                                          + "\": missing join/sort key on child image");
        }
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        key.put(projection.target().partitionKey(), AttributeValueMaps.toAttributeValue(joinVal));
        key.put(projection.target().sortKey(), AttributeValueMaps.toAttributeValue(skVal));
        return key;
    }

    static boolean isLeftOnlyKey(Map<String, AttributeValue> key, JoinProjectionSpec projection) {
        AttributeValue sk = key.get(projection.target().sortKey());
        return sk != null && LEFT_ONLY_SORT_KEY.equals(sk.s());
    }

    private static boolean keepsLeftOnlyRows(JoinType type) {
        return type == JoinType.LEFT || type == JoinType.FULL;
    }

    private static boolean hasOtherChildRows(List<Map<String, AttributeValue>> siblingKeys,
                                             Map<String, AttributeValue> removedKey,
                                             JoinProjectionSpec projection) {
        String pkName = projection.target().partitionKey();
        String skName = projection.target().sortKey();
        AttributeValue removedPk = removedKey.get(pkName);
        AttributeValue removedSk = removedKey.get(skName);
        for (Map<String, AttributeValue> sibling : siblingKeys) {
            if (isLeftOnlyKey(sibling, projection)) {
                continue;
            }
            AttributeValue pk = sibling.get(pkName);
            AttributeValue sk = sibling.get(skName);
            if (Objects.equals(attrString(pk), attrString(removedPk))
                && Objects.equals(attrString(sk), attrString(removedSk))) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static List<Map<String, AttributeValue>> childKeysOnly(
        List<Map<String, AttributeValue>> keys, JoinProjectionSpec projection) {
        List<Map<String, AttributeValue>> out = new ArrayList<>();
        for (Map<String, AttributeValue> key : keys) {
            if (!isLeftOnlyKey(key, projection)) {
                out.add(key);
            }
        }
        return out;
    }

    private static List<Map<String, AttributeValue>> leftOnlyKeysOnly(
        List<Map<String, AttributeValue>> keys, JoinProjectionSpec projection) {
        List<Map<String, AttributeValue>> out = new ArrayList<>();
        for (Map<String, AttributeValue> key : keys) {
            if (isLeftOnlyKey(key, projection)) {
                out.add(key);
            }
        }
        return out;
    }

    private static Map<String, AttributeValue> projectLeftAttrs(JoinProjectionSpec projection,
                                                                Map<String, Object> parent) {
        Map<String, AttributeValue> out = new LinkedHashMap<>();
        for (String field : projection.leftFields()) {
            Object v = parent.get(field);
            if (v != null) {
                out.put(field, AttributeValueMaps.toAttributeValue(v));
            }
        }
        return out;
    }

    private static String attrString(AttributeValue v) {
        if (v == null) {
            return null;
        }
        if (v.s() != null) {
            return v.s();
        }
        if (v.n() != null) {
            return v.n();
        }
        return String.valueOf(v);
    }
}
