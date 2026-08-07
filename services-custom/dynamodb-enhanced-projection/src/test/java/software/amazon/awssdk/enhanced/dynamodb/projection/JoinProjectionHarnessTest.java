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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public class JoinProjectionHarnessTest {

    private static JoinProjectionSpec customersOrdersJoin(JoinType joinType) {
        return JoinProjections.builder("CustomersOrdersJoin")
                              .joinType(joinType)
                              .leftEntityType("Customer")
                              .rightEntityType("Order")
                              .leftJoinAttribute("customerId")
                              .rightJoinAttribute("customerId")
                              .rightSortKeyAttribute("orderId")
                              .leftFields("name", "region")
                              .rightFields("orderId", "amount")
                              .target(TargetTable.of("CustomersOrdersJoin", "customerId", "orderId"))
                              .build();
    }

    private static JoinProjectionSpec customersOrdersJoin() {
        return customersOrdersJoin(JoinType.INNER);
    }

    private static Map<String, Object> customer(String id, String name, String region) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("customerId", id);
        customer.put("name", name);
        customer.put("region", region);
        return customer;
    }

    private static Map<String, Object> order(String customerId, String orderId, int amount) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("customerId", customerId);
        order.put("orderId", orderId);
        order.put("amount", amount);
        return order;
    }

    private static boolean isLeftOnly(Map<String, AttributeValue> row) {
        AttributeValue sk = row.get("orderId");
        return sk != null && JoinProjectionApplyEngine.LEFT_ONLY_SORT_KEY.equals(sk.s());
    }

    @Test
    public void orderInsertCreatesJoinRowWithParentAttrs() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin());

        harness.applyRecord(StreamRecordDecoder.insert("Customer", "c1", customer("c1", "Alice", "EU")));

        JoinApplyOutcome outcome =
            harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 100)));

        assertThat(outcome.kind()).isEqualTo(JoinApplyOutcome.Kind.WRITES);
        List<Map<String, AttributeValue>> rows = harness.getJoinRows("c1");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name").s()).isEqualTo("Alice");
        assertThat(rows.get(0).get("region").s()).isEqualTo("EU");
        assertThat(rows.get(0).get("orderId").s()).isEqualTo("o1");
        assertThat(rows.get(0).get("amount").n()).isEqualTo("100");
    }

    @Test
    public void orderWithoutParentIsSkippedForInner() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin());
        JoinApplyOutcome outcome =
            harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 10)));
        assertThat(outcome.kind()).isEqualTo(JoinApplyOutcome.Kind.SKIPPED);
        assertThat(((JoinApplyOutcome.Skipped) outcome).reason())
            .isEqualTo(ApplyOutcome.SkipReason.MISSING_PARENT);
        assertThat(harness.getJoinRows("c1")).isEmpty();
    }

    @Test
    public void customerModifyFansOutToJoinRows() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin());
        Map<String, Object> c = customer("c1", "Alice", "EU");
        c.put("_v", "01A");
        harness.applyRecord(StreamRecordDecoder.insert("Customer", "c1", c));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 50)));

        Map<String, Object> next = new LinkedHashMap<>(c);
        next.put("name", "Alicia");
        next.put("region", "US");
        next.put("_v", "01B");
        harness.applyRecord(StreamRecordDecoder.modify("Customer", "c1", c, next));

        List<Map<String, AttributeValue>> rows = harness.getJoinRows("c1");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name").s()).isEqualTo("Alicia");
        assertThat(rows.get(0).get("region").s()).isEqualTo("US");
        assertThat(rows.get(0).get("amount").n()).isEqualTo("50");
    }

    @Test
    public void orderRemoveDeletesJoinRow() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin());
        harness.applyRecord(StreamRecordDecoder.insert("Customer", "c1", customer("c1", "Alice", "EU")));

        Map<String, Object> o = order("c1", "o1", 10);
        o.put("_v", "01A");
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", o));
        harness.applyRecord(StreamRecordDecoder.remove("Order", "c1#o1", o));

        assertThat(harness.getJoinRows("c1")).isEmpty();
    }

    @Test
    public void customerRemoveClearsJoinRowsInner() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin());
        Map<String, Object> c = customer("c1", "Alice", "EU");
        c.put("_v", "01A");
        harness.applyRecord(StreamRecordDecoder.insert("Customer", "c1", c));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 10)));

        harness.applyRecord(StreamRecordDecoder.remove("Customer", "c1", c));
        assertThat(harness.getJoinRows("c1")).isEmpty();
        assertThat(harness.getParent("c1")).isNull();
    }

    @Test
    public void wrongEntityTypeIsSkipped() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin());
        Map<String, Object> item = new HashMap<>();
        item.put("customerId", "c1");
        JoinApplyOutcome outcome =
            harness.applyRecord(StreamRecordDecoder.insert("Product", "p1", item));
        assertThat(outcome.kind()).isEqualTo(JoinApplyOutcome.Kind.SKIPPED);
        assertThat(((JoinApplyOutcome.Skipped) outcome).reason())
            .isEqualTo(ApplyOutcome.SkipReason.WRONG_ENTITY_TYPE);
    }

    @Test
    public void applyEnginePutRequestShape() {
        JoinProjectionSpec spec = customersOrdersJoin();
        Map<String, Object> parent = customer("c1", "Alice", "EU");
        Map<String, Object> child = order("c1", "o1", 25);
        NormalizedRecord record = StreamRecordDecoder.insert("Order", "c1#o1", child);

        JoinApplyOutcome outcome = JoinProjectionApplyEngine.plan(spec, record, k -> parent, null);

        assertThat(outcome.kind()).isEqualTo(JoinApplyOutcome.Kind.WRITES);
        JoinApplyOutcome.Write write = ((JoinApplyOutcome.Writes) outcome).writes().get(0);
        assertThat(write.op()).isEqualTo(JoinApplyOutcome.Write.Op.PUT);
        PutItemRequest put = ((JoinApplyOutcome.Write.Put) write).request();
        assertThat(put.tableName()).isEqualTo("CustomersOrdersJoin");
        assertThat(put.item()).containsKeys("customerId", "orderId", "name", "region", "amount");
    }

    @Test
    public void leftJoinCreatesLeftOnlyRowForParentWithoutChildren() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin(JoinType.LEFT));
        harness.applyRecord(StreamRecordDecoder.insert("Customer", "c1", customer("c1", "Alice", "EU")));

        List<Map<String, AttributeValue>> rows = harness.getJoinRows("c1");
        assertThat(rows).hasSize(1);
        assertThat(isLeftOnly(rows.get(0))).isTrue();
        assertThat(rows.get(0).get("name").s()).isEqualTo("Alice");
        assertThat(rows.get(0).get("_leftOnly").bool()).isTrue();
    }

    @Test
    public void leftJoinReplacesLeftOnlyWhenChildArrives() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin(JoinType.LEFT));
        harness.applyRecord(StreamRecordDecoder.insert("Customer", "c1", customer("c1", "Alice", "EU")));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 40)));

        List<Map<String, AttributeValue>> rows = harness.getJoinRows("c1");
        assertThat(rows).hasSize(1);
        assertThat(isLeftOnly(rows.get(0))).isFalse();
        assertThat(rows.get(0).get("orderId").s()).isEqualTo("o1");
        assertThat(rows.get(0).get("name").s()).isEqualTo("Alice");
    }

    @Test
    public void leftJoinRestoresLeftOnlyWhenLastChildRemoved() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin(JoinType.LEFT));
        harness.applyRecord(StreamRecordDecoder.insert("Customer", "c1", customer("c1", "Alice", "EU")));
        Map<String, Object> o = order("c1", "o1", 40);
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", o));
        harness.applyRecord(StreamRecordDecoder.remove("Order", "c1#o1", o));

        List<Map<String, AttributeValue>> rows = harness.getJoinRows("c1");
        assertThat(rows).hasSize(1);
        assertThat(isLeftOnly(rows.get(0))).isTrue();
        assertThat(rows.get(0).get("name").s()).isEqualTo("Alice");
    }

    @Test
    public void leftJoinSkipsOrphanChild() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin(JoinType.LEFT));
        JoinApplyOutcome outcome =
            harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 10)));
        assertThat(outcome.kind()).isEqualTo(JoinApplyOutcome.Kind.SKIPPED);
        assertThat(((JoinApplyOutcome.Skipped) outcome).reason())
            .isEqualTo(ApplyOutcome.SkipReason.MISSING_PARENT);
        assertThat(harness.getJoinRows("c1")).isEmpty();
    }

    @Test
    public void rightJoinMaterialisesOrphanChild() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin(JoinType.RIGHT));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 15)));

        List<Map<String, AttributeValue>> rows = harness.getJoinRows("c1");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("orderId").s()).isEqualTo("o1");
        assertThat(rows.get(0).get("amount").n()).isEqualTo("15");
        assertThat(rows.get(0).get("name")).isNull();
    }

    @Test
    public void rightJoinFillsLeftAttrsWhenParentArrives() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin(JoinType.RIGHT));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 15)));
        harness.applyRecord(StreamRecordDecoder.insert("Customer", "c1", customer("c1", "Alice", "EU")));

        List<Map<String, AttributeValue>> rows = harness.getJoinRows("c1");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name").s()).isEqualTo("Alice");
        assertThat(rows.get(0).get("region").s()).isEqualTo("EU");
        assertThat(rows.get(0).get("amount").n()).isEqualTo("15");
    }

    @Test
    public void rightJoinClearsLeftAttrsOnParentRemove() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin(JoinType.RIGHT));
        Map<String, Object> c = customer("c1", "Alice", "EU");
        harness.applyRecord(StreamRecordDecoder.insert("Customer", "c1", c));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 15)));
        harness.applyRecord(StreamRecordDecoder.remove("Customer", "c1", c));

        List<Map<String, AttributeValue>> rows = harness.getJoinRows("c1");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("orderId").s()).isEqualTo("o1");
        assertThat(rows.get(0).get("amount").n()).isEqualTo("15");
        assertThat(rows.get(0).get("name")).isNull();
        assertThat(rows.get(0).get("region")).isNull();
    }

    @Test
    public void fullJoinKeepsLeftOnlyAndOrphanChildren() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin(JoinType.FULL));

        // Parent without children → left-only row
        harness.applyRecord(StreamRecordDecoder.insert("Customer", "c1", customer("c1", "Alice", "EU")));
        assertThat(harness.getJoinRows("c1")).hasSize(1);
        assertThat(isLeftOnly(harness.getJoinRows("c1").get(0))).isTrue();

        // Orphan child on another key
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c2#o9", order("c2", "o9", 99)));
        List<Map<String, AttributeValue>> orphan = harness.getJoinRows("c2");
        assertThat(orphan).hasSize(1);
        assertThat(orphan.get(0).get("name")).isNull();
        assertThat(orphan.get(0).get("amount").n()).isEqualTo("99");

        // Child for c1 replaces left-only
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 5)));
        List<Map<String, AttributeValue>> c1 = harness.getJoinRows("c1");
        assertThat(c1).hasSize(1);
        assertThat(isLeftOnly(c1.get(0))).isFalse();
        assertThat(c1.get(0).get("name").s()).isEqualTo("Alice");
    }

    @Test
    public void fullJoinParentRemoveClearsLeftButKeepsChildren() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(customersOrdersJoin(JoinType.FULL));
        Map<String, Object> c = customer("c1", "Alice", "EU");
        harness.applyRecord(StreamRecordDecoder.insert("Customer", "c1", c));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 5)));
        harness.applyRecord(StreamRecordDecoder.remove("Customer", "c1", c));

        List<Map<String, AttributeValue>> rows = harness.getJoinRows("c1");
        assertThat(rows).hasSize(1);
        assertThat(isLeftOnly(rows.get(0))).isFalse();
        assertThat(rows.get(0).get("amount").n()).isEqualTo("5");
        assertThat(rows.get(0).get("name")).isNull();
    }
}
