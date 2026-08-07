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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ProjectionHarnessTest {

    private static ProjectionSpec ordersByCustomer() {
        return Projections.builder("OrdersByCustomer")
                          .sourceEntityType("Order")
                          .groupBy("customerId")
                          .target(TargetTable.of("OrdersByCustomer", "customerId"))
                          .field("orderCount", AggregateDefinition.count())
                          .field("totalAmount", AggregateDefinition.sum("amount"))
                          .field("paidOrders",
                                 AggregateDefinition.count(ProjectionPredicate.eq("status", "paid")))
                          .field("paidRevenue",
                                 AggregateDefinition.sum("amount",
                                                         ProjectionPredicate.eq("status", "paid")))
                          .build();
    }

    @Test
    public void insertIncrementsCountAndSum() {
        ProjectionHarness harness = ProjectionHarness.of(ordersByCustomer());
        Map<String, Object> order = new HashMap<>();
        order.put("customerId", "c1");
        order.put("orderId", "o1");
        order.put("amount", 100);
        order.put("status", "paid");

        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order));

        Map<String, Number> aggs = harness.getAggregates(Collections.singletonMap("customerId", "c1"));
        assertThat(aggs.get("orderCount").intValue()).isEqualTo(1);
        assertThat(aggs.get("totalAmount").intValue()).isEqualTo(100);
        assertThat(aggs.get("paidOrders").intValue()).isEqualTo(1);
        assertThat(aggs.get("paidRevenue").intValue()).isEqualTo(100);
        assertThat(aggs.get("totalAmount").doubleValue() / aggs.get("orderCount").doubleValue())
            .isEqualTo(100.0);
    }

    @Test
    public void modifyStatusChangesFilteredAggregates() {
        ProjectionHarness harness = ProjectionHarness.of(ordersByCustomer());
        Map<String, Object> prev = new HashMap<>();
        prev.put("customerId", "c1");
        prev.put("orderId", "o1");
        prev.put("amount", 50);
        prev.put("status", "pending");
        prev.put("_v", "01AAA");

        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", prev));

        Map<String, Object> next = new HashMap<>(prev);
        next.put("status", "paid");
        next.put("_v", "01BBB");
        harness.applyRecord(StreamRecordDecoder.modify("Order", "c1#o1", prev, next));

        Map<String, Number> aggs = harness.getAggregates(Collections.singletonMap("customerId", "c1"));
        assertThat(aggs.get("orderCount").intValue()).isEqualTo(1);
        assertThat(aggs.get("paidOrders").intValue()).isEqualTo(1);
        assertThat(aggs.get("paidRevenue").intValue()).isEqualTo(50);
    }

    @Test
    public void duplicateInsertIsIdempotent() {
        ProjectionHarness harness = ProjectionHarness.of(ordersByCustomer());
        Map<String, Object> order = new HashMap<>();
        order.put("customerId", "c1");
        order.put("orderId", "o1");
        order.put("amount", 10);
        order.put("status", "paid");
        order.put("_v", "01FIXED");

        NormalizedRecord record = NormalizedRecord.builder()
                                                  .entityType("Order")
                                                  .eventName(NormalizedRecord.EventName.INSERT)
                                                  .next(order)
                                                  .sourceItemKey("c1#o1")
                                                  .sourceVersion("01FIXED")
                                                  .build();
        harness.applyRecord(record);
        harness.applyRecord(record);

        Map<String, Number> aggs = harness.getAggregates(Collections.singletonMap("customerId", "c1"));
        assertThat(aggs.get("orderCount").intValue()).isEqualTo(1);
    }

    @Test
    public void groupKeyMutationFails() {
        ProjectionHarness harness = ProjectionHarness.of(ordersByCustomer());
        Map<String, Object> prev = new HashMap<>();
        prev.put("customerId", "c1");
        prev.put("orderId", "o1");
        prev.put("amount", 10);
        prev.put("_v", "01A");
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", prev));

        Map<String, Object> next = new HashMap<>(prev);
        next.put("customerId", "c2");
        next.put("_v", "01B");

        assertThatThrownBy(() -> harness.applyRecord(
            StreamRecordDecoder.modify("Order", "c1#o1", prev, next)))
            .isInstanceOf(GroupKeyMutationException.class);
    }

    @Test
    public void removeDecrementsAggregates() {
        ProjectionHarness harness = ProjectionHarness.of(ordersByCustomer());
        Map<String, Object> order = new HashMap<>();
        order.put("customerId", "c1");
        order.put("orderId", "o1");
        order.put("amount", 40);
        order.put("status", "paid");
        order.put("_v", "01A");
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order));
        harness.applyRecord(StreamRecordDecoder.remove("Order", "c1#o1", order));

        Map<String, Number> aggs = harness.getAggregates(Collections.singletonMap("customerId", "c1"));
        assertThat(aggs.get("orderCount").intValue()).isEqualTo(0);
        assertThat(aggs.get("totalAmount").intValue()).isEqualTo(0);
        assertThat(aggs.get("paidOrders").intValue()).isEqualTo(0);
    }

    private static ProjectionSpec ordersWithAvgMinMax() {
        return Projections.builder("OrdersByCustomerExt")
                          .sourceEntityType("Order")
                          .groupBy("customerId")
                          .target(TargetTable.of("OrdersByCustomer", "customerId"))
                          .field("avgAmount", AggregateDefinition.avg("amount"))
                          .field("minAmount", AggregateDefinition.min("amount"))
                          .field("maxAmount", AggregateDefinition.max("amount"))
                          .build();
    }

    @Test
    public void avgIsStoredFromSumAndCount() {
        ProjectionHarness harness = ProjectionHarness.of(ordersWithAvgMinMax());
        Map<String, Object> o1 = order("c1", "o1", 10);
        Map<String, Object> o2 = order("c1", "o2", 30);
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", o1));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o2", o2));

        assertThat(harness.storedAvg(Collections.singletonMap("customerId", "c1"), "avgAmount"))
            .isEqualTo(20.0);
    }

    @Test
    public void minMaxTrackExtremesAndRecomputeOnDeleteOfMin() {
        ProjectionHarness harness = ProjectionHarness.of(ordersWithAvgMinMax());
        Map<String, Object> o1 = order("c1", "o1", 10);
        o1.put("_v", "01A");
        Map<String, Object> o2 = order("c1", "o2", 50);
        o2.put("_v", "01B");
        Map<String, Object> o3 = order("c1", "o3", 30);
        o3.put("_v", "01C");

        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", o1));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o2", o2));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o3", o3));

        Map<String, Number> aggs = harness.getAggregates(Collections.singletonMap("customerId", "c1"));
        assertThat(aggs.get("minAmount").intValue()).isEqualTo(10);
        assertThat(aggs.get("maxAmount").intValue()).isEqualTo(50);

        harness.applyRecord(StreamRecordDecoder.remove("Order", "c1#o1", o1));
        aggs = harness.getAggregates(Collections.singletonMap("customerId", "c1"));
        assertThat(aggs.get("minAmount").intValue()).isEqualTo(30);
        assertThat(aggs.get("maxAmount").intValue()).isEqualTo(50);
    }

    @Test
    public void deleteNonExtremeDoesNotChangeMinMax() {
        ProjectionHarness harness = ProjectionHarness.of(ordersWithAvgMinMax());
        Map<String, Object> o1 = order("c1", "o1", 10);
        o1.put("_v", "01A");
        Map<String, Object> o2 = order("c1", "o2", 50);
        o2.put("_v", "01B");
        Map<String, Object> o3 = order("c1", "o3", 30);
        o3.put("_v", "01C");

        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", o1));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o2", o2));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o3", o3));
        harness.applyRecord(StreamRecordDecoder.remove("Order", "c1#o3", o3));

        Map<String, Number> aggs = harness.getAggregates(Collections.singletonMap("customerId", "c1"));
        assertThat(aggs.get("minAmount").intValue()).isEqualTo(10);
        assertThat(aggs.get("maxAmount").intValue()).isEqualTo(50);
    }

    private static Map<String, Object> order(String customerId, String orderId, int amount) {
        Map<String, Object> order = new HashMap<>();
        order.put("customerId", customerId);
        order.put("orderId", orderId);
        order.put("amount", amount);
        return order;
    }
}
