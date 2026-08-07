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

import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.query.condition.Condition;

public class CarryForwardProjectionTest {

    @Test
    public void carryForwardRegionEnablesHavingFilter() {
        ProjectionSpec spec = Projections.builder("OrdersByCustomer")
                                         .sourceEntityType("Order")
                                         .groupBy("customerId")
                                         .target(TargetTable.of("OrdersByCustomer", "customerId"))
                                         .field("orderCount", AggregateDefinition.count())
                                         .carryForward("region")
                                         .build();
        ProjectionHarness harness = ProjectionHarness.of(spec);
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "EU")));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c2#o1", order("c2", "US")));

        SummaryPage eu = harness.query(SummaryQuery.builder()
                                                     .having(Condition.eq("region", "EU"))
                                                     .build());
        assertThat(eu.rows()).hasSize(1);
        assertThat(eu.rows().get(0).key().get("customerId")).isEqualTo("c1");
        assertThat(eu.rows().get(0).attributes().get("region")).isEqualTo("EU");
    }

    private static Map<String, Object> order(String customerId, String region) {
        Map<String, Object> order = new java.util.LinkedHashMap<>();
        order.put("customerId", customerId);
        order.put("orderId", "o1");
        order.put("amount", 10);
        order.put("region", region);
        order.put("_v", VersionGenerator.next());
        return order;
    }
}
