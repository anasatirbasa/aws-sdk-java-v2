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
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package software.amazon.awssdk.enhanced.dynamodb.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class JoinProjectionApplyEngineTest {

    @Test
    public void needsSiblingKeysFalseForInnerOrderInsert() {
        JoinProjectionSpec spec = JoinProjections.builder("J")
            .joinType(JoinType.INNER)
            .leftEntityType("Customer")
            .rightEntityType("Order")
            .leftJoinAttribute("customerId")
            .rightJoinAttribute("customerId")
            .rightSortKeyAttribute("orderId")
            .leftFields("name")
            .rightFields("amount")
            .target(TargetTable.of("J", "customerId", "orderId"))
            .build();
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("customerId", "c1");
        order.put("orderId", "o1");
        order.put("amount", 10);
        NormalizedRecord record = StreamRecordDecoder.insert("Order", "c1#o1", order);
        assertThat(JoinProjectionApplyEngine.needsSiblingKeys(spec, record)).isFalse();
    }

    @Test
    public void embeddedParentUsedWhenPresentOnChild() {
        JoinProjectionHarness harness = JoinProjectionHarness.of(
            JoinProjections.builder("J")
                .joinType(JoinType.INNER)
                .leftEntityType("Customer")
                .rightEntityType("Order")
                .leftJoinAttribute("customerId")
                .rightJoinAttribute("customerId")
                .rightSortKeyAttribute("orderId")
                .leftFields("name", "region")
                .rightFields("orderId", "amount")
                .target(TargetTable.of("J", "customerId", "orderId"))
                .build());
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("customerId", "c1");
        order.put("orderId", "o1");
        order.put("amount", 10);
        order.put("name", "Embedded");
        order.put("region", "EU");
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order));
        assertThat(harness.getJoinRows("c1")).hasSize(1);
        assertThat(harness.getJoinRows("c1").get(0).get("name").s()).isEqualTo("Embedded");
    }
}
