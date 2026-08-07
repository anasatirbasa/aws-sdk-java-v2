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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.utils.Validate;

/**
 * In-memory join projection harness (no DynamoDB). Maintains a parent store and join rows;
 * applies the same plan as {@link JoinProjectionApplyEngine} for all {@link JoinType}s.
 */
@SdkPublicApi
public final class JoinProjectionHarness implements ParentResolver {

    private static final Pattern SET_PAIR = Pattern.compile("(#[\\w]+)\\s*=\\s*(:[\\w]+)");

    private final JoinProjectionSpec projection;
    private final Map<String, Map<String, Object>> parents = new ConcurrentHashMap<>();
    private final Map<String, Map<String, AttributeValue>> joinRows = new ConcurrentHashMap<>();
    /**
     * joinKey value → row key ids for O(k) sibling lookup.
     */
    private final Map<String, List<String>> joinKeyIndex = new ConcurrentHashMap<>();
    private BenchmarkSharedJoinRowStore sharedJoinRows;
    private final Set<String> indexedRowIds = ConcurrentHashMap.newKeySet();

    public JoinProjectionHarness(JoinProjectionSpec projection) {
        this.projection = Validate.paramNotNull(projection, "projection");
    }

    public static JoinProjectionHarness of(JoinProjectionSpec projection) {
        return new JoinProjectionHarness(projection);
    }

    @Override
    public Map<String, Object> findParent(String joinKeyValue) {
        return parents.get(joinKeyValue);
    }

    /**
     * Applies a left- or right-entity record. Left INSERT/MODIFY also upserts the parent
     * store used for subsequent child joins.
     */
    public JoinApplyOutcome applyRecord(NormalizedRecord record) {
        if (projection.leftEntityType().equals(record.entityType())) {
            updateParentStore(record);
        }

        List<Map<String, AttributeValue>> existingKeys = JoinProjectionApplyEngine.needsSiblingKeys(projection, record)
                                           ? joinKeysForRecord(record)
                                           : Collections.emptyList();

        JoinApplyOutcome outcome = JoinProjectionApplyEngine.plan(
            projection, record, this, existingKeys);
        if (outcome.kind() == JoinApplyOutcome.Kind.SKIPPED) {
            return outcome;
        }
        executeWrites((JoinApplyOutcome.Writes) outcome);
        return outcome;
    }

    public List<Map<String, AttributeValue>> getJoinRows(String joinKeyValue) {
        List<String> rowIds = joinKeyIndex.get(joinKeyValue);
        if (rowIds == null || rowIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, AttributeValue>> out = new ArrayList<>(rowIds.size());
        for (String rowId : rowIds) {
            Map<String, AttributeValue> row = resolveRow(rowId);
            if (row != null) {
                out.add(Collections.unmodifiableMap(row));
            }
        }
        return out;
    }

    /**
     * Paginates join rows for a partition key using an opaque offset cursor (in-memory stand-in for LEK).
     */
    public JoinPage queryPage(String joinKeyValue, int limit, String cursor) {
        List<Map<String, AttributeValue>> all = getJoinRows(joinKeyValue);
        int offset = 0;
        if (cursor != null && !cursor.isEmpty()) {
            try {
                offset = Integer.parseInt(cursor);
            } catch (NumberFormatException e) {
                offset = 0;
            }
        }
        if (offset >= all.size()) {
            return new JoinPage(Collections.emptyList(), null);
        }
        int end = Math.min(all.size(), offset + Math.max(1, limit));
        List<Map<String, AttributeValue>> page = new ArrayList<>(all.subList(offset, end));
        String next = end < all.size() ? String.valueOf(end) : null;
        return new JoinPage(page, next);
    }

    public Map<String, Object> getParent(String joinKeyValue) {
        Map<String, Object> parent = parents.get(joinKeyValue);
        return parent == null ? null : Collections.unmodifiableMap(parent);
    }

    public JoinProjectionSpec projection() {
        return projection;
    }

    /**
     * Benchmark bulk seed: share one physical row map per {@code rowId} across join-type harnesses.
     */
    @SdkInternalApi
    public void useSharedJoinRows(BenchmarkSharedJoinRowStore sharedJoinRows) {
        this.sharedJoinRows = Validate.paramNotNull(sharedJoinRows, "sharedJoinRows");
    }

    /**
     * Loads a precomputed join MV row (benchmark bulk seed). Takes ownership of {@code item}
     * (no copy) — caller must not mutate after load.
     */
    public void loadPrecomputedJoinRow(Map<String, AttributeValue> item) {
        Validate.paramNotNull(item, "item");
        String pkName = projection.target().partitionKey();
        String skName = projection.target().sortKey();
        putRowWithoutCopy(item, pkName, skName);
    }

    /**
     * Stores a parent image for {@link #findParent(String)} (benchmark bulk seed).
     */
    public void loadPrecomputedParent(Map<String, Object> parent) {
        Validate.paramNotNull(parent, "parent");
        Object key = parent.get(projection.leftJoinAttribute());
        if (key != null) {
            parents.put(String.valueOf(key), new LinkedHashMap<>(parent));
        }
    }

    /**
     * Updates a projected attribute on all join rows for a partition key (benchmark extension seed).
     */
    public void setJoinFieldForPartition(String joinKeyValue, String field, AttributeValue value) {
        Validate.paramNotBlank(joinKeyValue, "joinKeyValue");
        Validate.paramNotBlank(field, "field");
        List<String> rowIds = joinKeyIndex.get(joinKeyValue);
        if (rowIds == null) {
            return;
        }
        for (String rowId : rowIds) {
            Map<String, AttributeValue> row = resolveRow(rowId);
            if (row != null) {
                row.put(field, value);
            }
        }
    }

    private void updateParentStore(NormalizedRecord record) {
        if (record.eventName() == NormalizedRecord.EventName.REMOVE) {
            Map<String, Object> prev = record.prev();
            if (prev != null) {
                Object key = prev.get(projection.leftJoinAttribute());
                if (key != null) {
                    parents.remove(String.valueOf(key));
                }
            }
            return;
        }
        Map<String, Object> next = record.next() != null ? record.next() : record.activeImage();
        if (next == null) {
            return;
        }
        Object key = next.get(projection.leftJoinAttribute());
        if (key != null) {
            parents.put(String.valueOf(key), new LinkedHashMap<>(next));
        }
    }

    private List<Map<String, AttributeValue>> joinKeysForRecord(NormalizedRecord record) {
        Map<String, Object> image = record.activeImage();
        if (image == null) {
            return new ArrayList<>();
        }
        Object joinVal;
        if (projection.leftEntityType().equals(record.entityType())) {
            joinVal = image.get(projection.leftJoinAttribute());
        } else if (projection.rightEntityType().equals(record.entityType())) {
            joinVal = image.get(projection.rightJoinAttribute());
        } else {
            return new ArrayList<>();
        }
        if (joinVal == null) {
            return new ArrayList<>();
        }
        return joinKeysForJoinValue(String.valueOf(joinVal));
    }

    private List<Map<String, AttributeValue>> joinKeysForJoinValue(String joinKey) {
        List<String> rowIds = joinKeyIndex.get(joinKey);
        if (rowIds == null || rowIds.isEmpty()) {
            return new ArrayList<>();
        }
        String pkName = projection.target().partitionKey();
        String skName = projection.target().sortKey();
        List<Map<String, AttributeValue>> keys = new ArrayList<>(rowIds.size());
        for (String rowId : rowIds) {
            Map<String, AttributeValue> row = resolveRow(rowId);
            if (row == null) {
                continue;
            }
            Map<String, AttributeValue> key = new LinkedHashMap<>();
            key.put(pkName, row.get(pkName));
            key.put(skName, row.get(skName));
            keys.add(key);
        }
        return keys;
    }

    private void executeWrites(JoinApplyOutcome.Writes writes) {
        String pkName = projection.target().partitionKey();
        String skName = projection.target().sortKey();
        for (JoinApplyOutcome.Write write : writes.writes()) {
            if (write instanceof JoinApplyOutcome.Write.Put) {
                Map<String, AttributeValue> item =
                    ((JoinApplyOutcome.Write.Put) write).request().item();
                putRow(item, pkName, skName);
            } else if (write instanceof JoinApplyOutcome.Write.Delete) {
                Map<String, AttributeValue> key =
                    ((JoinApplyOutcome.Write.Delete) write).request().key();
                removeRow(key, pkName, skName);
            } else if (write instanceof JoinApplyOutcome.Write.Update) {
                applyUpdate(((JoinApplyOutcome.Write.Update) write).request(), pkName, skName);
            }
        }
    }

    private void putRow(Map<String, AttributeValue> item, String pkName, String skName) {
        putRowInternal(new LinkedHashMap<>(item), pkName, skName);
    }

    private void putRowWithoutCopy(Map<String, AttributeValue> item, String pkName, String skName) {
        putRowInternal(item, pkName, skName);
    }

    private Map<String, AttributeValue> resolveRow(String rowId) {
        if (sharedJoinRows != null) {
            return sharedJoinRows.get(rowId);
        }
        return joinRows.get(rowId);
    }

    private void putRowInternal(Map<String, AttributeValue> row, String pkName, String skName) {
        String rowId = keyId(row.get(pkName), row.get(skName));
        if (sharedJoinRows != null) {
            sharedJoinRows.register(rowId, row);
        } else {
            Map<String, AttributeValue> previous = joinRows.put(rowId, row);
            if (previous != null) {
                return;
            }
        }
        if (!indexedRowIds.add(rowId)) {
            return;
        }
        String joinKey = stringVal(row.get(pkName));
        joinKeyIndex.computeIfAbsent(joinKey, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(rowId);
    }

    private void removeRow(Map<String, AttributeValue> key, String pkName, String skName) {
        String rowId = keyId(key.get(pkName), key.get(skName));
        if (sharedJoinRows != null) {
            sharedJoinRows.remove(rowId);
        } else {
            joinRows.remove(rowId);
        }
        indexedRowIds.remove(rowId);
        String joinKey = stringVal(key.get(pkName));
        List<String> ids = joinKeyIndex.get(joinKey);
        if (ids != null) {
            ids.remove(rowId);
            if (ids.isEmpty()) {
                joinKeyIndex.remove(joinKey);
            }
        }
    }

    private void applyUpdate(UpdateItemRequest request, String pkName, String skName) {
        Map<String, AttributeValue> key = request.key();
        String rowId = keyId(key.get(pkName), key.get(skName));
        Map<String, AttributeValue> row = resolveRow(rowId);
        if (row == null) {
            return;
        }
        String expr = request.updateExpression();
        if (expr == null) {
            return;
        }
        Map<String, AttributeValue> values = request.expressionAttributeValues();
        Map<String, String> names = request.expressionAttributeNames();
        if (names == null) {
            return;
        }

        int setIdx = expr.indexOf("SET ");
        String removePart = setIdx >= 0 ? expr.substring(0, setIdx) : expr;
        String setPart = setIdx >= 0 ? expr.substring(setIdx + 4) : "";

        if (removePart.contains("REMOVE")) {
            String tokens = removePart.replace("REMOVE", "").trim();
            for (String token : tokens.split(",")) {
                String nameToken = token.trim();
                if (nameToken.isEmpty()) {
                    continue;
                }
                String attr = names.get(nameToken);
                if (attr != null) {
                    row.remove(attr);
                }
            }
        }

        if (!setPart.isEmpty() && values != null) {
            Matcher matcher = SET_PAIR.matcher(setPart);
            while (matcher.find()) {
                String nameToken = matcher.group(1);
                String valueToken = matcher.group(2);
                String attr = names.get(nameToken);
                AttributeValue v = values.get(valueToken);
                if (attr != null && v != null) {
                    row.put(attr, v);
                }
            }
        }
    }

    private static String keyId(AttributeValue pk, AttributeValue sk) {
        return stringVal(pk) + "#" + stringVal(sk);
    }

    private static String stringVal(AttributeValue v) {
        if (v == null) {
            return "";
        }
        if (v.s() != null) {
            return v.s();
        }
        if (v.n() != null) {
            return v.n();
        }
        return String.valueOf(v);
    }

    /**
     * Page of join rows with an opaque cursor for the next page.
     */
    public static final class JoinPage {
        private final List<Map<String, AttributeValue>> rows;
        private final String cursor;

        JoinPage(List<Map<String, AttributeValue>> rows, String cursor) {
            this.rows = rows;
            this.cursor = cursor;
        }

        public static JoinPage empty() {
            return new JoinPage(Collections.emptyList(), null);
        }

        public List<Map<String, AttributeValue>> rows() {
            return rows;
        }

        public String cursor() {
            return cursor;
        }

        public boolean hasMore() {
            return cursor != null;
        }
    }
}
