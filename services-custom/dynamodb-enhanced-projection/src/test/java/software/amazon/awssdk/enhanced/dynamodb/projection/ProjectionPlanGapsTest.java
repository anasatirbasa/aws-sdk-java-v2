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

public class ProjectionPlanGapsTest {

    @Test
    public void globalMinMaxRecomputesAfterDeleteOfMin() {
        ProjectionSpec global = Projections.builder("OrdersGlobal")
                                           .sourceEntityType("Order")
                                           .groupBy()
                                           .target(TargetTable.of("OrdersGlobal", "pk"))
                                           .field("minAmount", AggregateDefinition.min("amount"))
                                           .field("maxAmount", AggregateDefinition.max("amount"))
                                           .build();
        ProjectionHarness harness = ProjectionHarness.of(global);

        Map<String, Object> o1 = order("c1", "o1", 10);
        o1.put("_v", "01A");
        Map<String, Object> o2 = order("c1", "o2", 50);
        o2.put("_v", "01B");
        Map<String, Object> o3 = order("c2", "o3", 30);
        o3.put("_v", "01C");

        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", o1));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o2", o2));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c2#o3", o3));

        Map<String, Object> allKey = Collections.singletonMap("pk", "ALL");
        assertThat(harness.getAggregates(allKey).get("minAmount").intValue()).isEqualTo(10);
        assertThat(harness.getAggregates(allKey).get("maxAmount").intValue()).isEqualTo(50);

        harness.applyRecord(StreamRecordDecoder.remove("Order", "c1#o1", o1));
        assertThat(harness.getAggregates(allKey).get("minAmount").intValue()).isEqualTo(30);
        assertThat(harness.getAggregates(allKey).get("maxAmount").intValue()).isEqualTo(50);
    }

    @Test
    public void strictModeRejectsSummaryQuery() {
        ProjectionSpec spec = Projections.builder("OrdersByCustomer")
                                         .sourceEntityType("Order")
                                         .groupBy("customerId")
                                         .target(TargetTable.of("OrdersByCustomer", "customerId"))
                                         .field("orderCount", AggregateDefinition.count())
                                         .build();
        ProjectionHarness harness = ProjectionHarness.of(spec, ProjectionExecutionMode.STRICT_KEY_ONLY);
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 10)));

        assertThatThrownBy(() -> harness.query(SummaryQuery.builder().limit(10).build()))
            .isInstanceOf(ProjectionExecutionPolicyException.class);
    }

    @Test
    public void aggregateGsiPartitionKeyWrittenOnApply() {
        ProjectionSpec spec = Projections.builder("OrdersByCustomer")
                                         .sourceEntityType("Order")
                                         .groupBy("customerId")
                                         .target(TargetTable.of("OrdersByCustomer", "customerId"))
                                         .field("orderCount", AggregateDefinition.count())
                                         .field("totalAmount", AggregateDefinition.sum("amount"))
                                         .aggregateGsi(AggregateGsi.of(
                                             "byTotalAmount", "gsiPk", "ALL", "totalAmount"))
                                         .build();
        ProjectionHarness harness = ProjectionHarness.of(spec);
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 100)));

        Map<String, Object> key = Collections.singletonMap("customerId", "c1");
        assertThat(harness.gsiAttribute(key, "gsiPk")).isEqualTo("ALL");
        assertThat(harness.getAggregates(key).get("totalAmount").intValue()).isEqualTo(100);
    }

    @Test
    public void aggregationFunctionAccessor() {
        AggregateDefinition def = AggregateDefinition.sum("amount");
        assertThat(def.aggregationFunction())
            .isEqualTo(AggregateDefinition.AggregationFunction.SUM);
    }

    private static Map<String, Object> order(String customerId, String orderId, int amount) {
        Map<String, Object> order = new HashMap<>();
        order.put("customerId", customerId);
        order.put("orderId", orderId);
        order.put("amount", amount);
        return order;
    }
}
