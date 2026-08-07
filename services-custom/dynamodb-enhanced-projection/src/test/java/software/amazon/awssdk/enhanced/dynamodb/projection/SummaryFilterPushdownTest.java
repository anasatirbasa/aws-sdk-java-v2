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

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.query.condition.Condition;

public class SummaryFilterPushdownTest {

    @Test
    public void pushesGreaterThan() {
        SummaryFilterPushdown.tryPushdown(Condition.gt("orderCount", 500)).ifPresent(p -> {
            assertThat(p.filterExpression()).isEqualTo("#f1 > :v0");
            assertThat(p.expressionAttributeNames()).containsEntry("#f1", "orderCount");
            assertThat(p.expressionAttributeValues()).containsKey(":v0");
        });
    }

    @Test
    public void pushesAndOfComparators() {
        Condition condition = Condition.gt("orderCount", 500).and(Condition.eq("region", "EU"));
        SummaryFilterPushdown.tryPushdown(condition).ifPresent(p -> {
            assertThat(p.filterExpression()).contains("AND");
            assertThat(p.expressionAttributeNames()).containsValue("orderCount");
            assertThat(p.expressionAttributeNames()).containsValue("region");
        });
    }

    @Test
    public void rejectsOr() {
        Condition condition = Condition.gt("orderCount", 500).or(Condition.eq("region", "EU"));
        assertThat(SummaryFilterPushdown.tryPushdown(condition)).isEmpty();
    }

    @Test
    public void rejectsBetween() {
        assertThat(SummaryFilterPushdown.tryPushdown(Condition.between("orderCount", 1, 10))).isEmpty();
    }
}
