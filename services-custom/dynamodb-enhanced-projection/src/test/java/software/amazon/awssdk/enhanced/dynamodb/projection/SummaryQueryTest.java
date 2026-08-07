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

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.query.condition.Condition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class SummaryQueryTest {

    private static ProjectionSpec spec() {
        return Projections.builder("OrdersByCustomer")
                          .sourceEntityType("Order")
                          .groupBy("customerId")
                          .target(TargetTable.of("OrdersByCustomer", "customerId"))
                          .field("orderCount", AggregateDefinition.count())
                          .field("totalAmount", AggregateDefinition.sum("amount"))
                          .build();
    }

    private static ProjectionHarness seededHarness() {
        ProjectionHarness harness = ProjectionHarness.of(spec());
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 100)));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c2#o1", order("c2", "o1", 50)));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c2#o2", order("c2", "o2", 50)));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c3#o1", order("c3", "o1", 10)));
        return harness;
    }

    @Test
    public void havingFiltersByAggregate() {
        ProjectionHarness harness = seededHarness();
        SummaryPage page = harness.query(SummaryQuery.builder()
                                                     .having(ProjectionPredicate.gte("orderCount", 2))
                                                     .build());
        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().get(0).key().get("customerId")).isEqualTo("c2");
        assertThat(page.cursor()).isNull();
    }

    @Test
    public void orderByAggregateDesc() {
        ProjectionHarness harness = seededHarness();
        SummaryPage page = harness.query(SummaryQuery.builder()
                                                     .orderByAggregate("totalAmount", SortDirection.DESC)
                                                     .build());
        assertThat(page.rows()).hasSize(3);
        assertThat(page.rows().get(0).key().get("customerId")).isEqualTo("c1");
        assertThat(page.rows().get(1).key().get("customerId")).isEqualTo("c2");
        assertThat(page.rows().get(2).key().get("customerId")).isEqualTo("c3");
    }

    @Test
    public void limitAndOffsetCursorPaginateAfterHavingAndOrderBy() {
        ProjectionHarness harness = seededHarness();
        SummaryQuery base = SummaryQuery.builder()
                                        .orderByAggregate("totalAmount", SortDirection.DESC)
                                        .limit(2)
                                        .build();
        SummaryPage page1 = harness.query(base);
        assertThat(page1.rows()).hasSize(2);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.rows().get(0).key().get("customerId")).isEqualTo("c1");
        assertThat(page1.rows().get(1).key().get("customerId")).isEqualTo("c2");

        SummaryPage page2 = harness.query(SummaryQuery.builder()
                                                      .orderByAggregate("totalAmount", SortDirection.DESC)
                                                      .limit(2)
                                                      .cursor(page1.cursor())
                                                      .build());
        assertThat(page2.rows()).hasSize(1);
        assertThat(page2.hasMore()).isFalse();
        assertThat(page2.rows().get(0).key().get("customerId")).isEqualTo("c3");
    }

    @Test
    public void invalidCursorThrows() {
        ProjectionHarness harness = seededHarness();
        assertThatThrownBy(() -> harness.query(SummaryQuery.builder()
                                                           .cursor("not-a-valid-cursor")
                                                           .build()))
            .isInstanceOf(ProjectionException.class);
    }

    @Test
    public void havingWithConditionBetween() {
        ProjectionHarness harness = seededHarness();
        SummaryPage page = harness.query(SummaryQuery.builder()
                                                     .having(Condition.between("orderCount", 1, 1))
                                                     .build());
        assertThat(page.rows()).hasSize(2);
    }

    @Test
    public void havingWithConditionOr() {
        ProjectionHarness harness = seededHarness();
        SummaryPage page = harness.query(SummaryQuery.builder()
                                                     .having(Condition.eq("orderCount", 1)
                                                                      .or(Condition.eq("orderCount", 2)))
                                                     .build());
        assertThat(page.rows()).hasSize(3);
    }

    @Test
    public void havingWithConditionNot() {
        ProjectionHarness harness = seededHarness();
        SummaryPage page = harness.query(SummaryQuery.builder()
                                                     .having(Condition.eq("orderCount", 1).not())
                                                     .build());
        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().get(0).key().get("customerId")).isEqualTo("c2");
    }

    @Test
    public void projectionCursorsRoundTripExclusiveStartKey() {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("customerId", AttributeValue.builder().s("c1").build());
        String cursor = ProjectionCursors.encodeExclusiveStartKey(key);
        Map<String, AttributeValue> decoded = ProjectionCursors.decodeExclusiveStartKey(cursor);
        assertThat(decoded.get("customerId").s()).isEqualTo("c1");
    }

    private static Map<String, Object> order(String customerId, String orderId, int amount) {
        Map<String, Object> order = new HashMap<>();
        order.put("customerId", customerId);
        order.put("orderId", orderId);
        order.put("amount", amount);
        return order;
    }
}
