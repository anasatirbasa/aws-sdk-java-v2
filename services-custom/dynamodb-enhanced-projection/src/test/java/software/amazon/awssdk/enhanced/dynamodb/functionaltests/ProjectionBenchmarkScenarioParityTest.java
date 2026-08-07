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

package software.amazon.awssdk.enhanced.dynamodb.functionaltests;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionApplyEngine;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionSpec;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjections;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinType;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.SortDirection;
import software.amazon.awssdk.enhanced.dynamodb.projection.StreamRecordDecoder;
import software.amazon.awssdk.enhanced.dynamodb.projection.SummaryPage;
import software.amazon.awssdk.enhanced.dynamodb.projection.SummaryQuery;
import software.amazon.awssdk.enhanced.dynamodb.projection.TargetTable;
import software.amazon.awssdk.enhanced.dynamodb.projection.VersionGenerator;
import software.amazon.awssdk.enhanced.dynamodb.query.condition.Condition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Row-count parity for benchmark extension scenarios (#10, #15, #25–35) on small harness datasets.
 */
public class ProjectionBenchmarkScenarioParityTest {

    @Test
    public void havingUsesOrdersPerCustomerMinusOneAt500() {
        int ordersPerCustomer = 500;
        int threshold = Math.min(500, Math.max(0, ordersPerCustomer - 1));
        ProjectionHarness harness = buildHarness(10, ordersPerCustomer);
        SummaryPage page = harness.query(SummaryQuery.builder()
                                                     .having(Condition.gt("orderCount", threshold))
                                                     .limit(20)
                                                     .build());
        assertThat(page.rows()).hasSize(10);
    }

    @Test
    public void euRegionFilterViaCarryForward() {
        ProjectionHarness harness = buildHarness(100, 5);
        SummaryPage page = harness.query(SummaryQuery.builder()
                                                     .having(Condition.eq("region", "EU"))
                                                     .limit(500)
                                                     .build());
        assertThat(page.rows()).hasSize(50);
    }

    @Test
    public void filteredLargeOrdersMatchAmountThreshold() {
        ProjectionHarness harness = buildHarness(1, 100);
        Map<String, Number> aggs = harness.getAggregates(Collections.singletonMap("customerId", "c1"));
        assertThat(aggs.get("largeOrders").intValue()).isEqualTo(51);
        assertThat(aggs.get("largeRevenue").intValue()).isGreaterThan(0);
    }

    @Test
    public void summaryPaginationHavingPage2ReturnsTenRows() {
        int ordersPerCustomer = 100;
        int threshold = ordersPerCustomer - 1;
        ProjectionHarness harness = buildHarness(20, ordersPerCustomer);
        SummaryPage page1 = harness.query(SummaryQuery.builder()
                                                      .having(Condition.gt("orderCount", threshold))
                                                      .orderByAggregate("totalAmount", SortDirection.DESC)
                                                      .limit(10)
                                                      .build());
        assertThat(page1.rows()).hasSize(10);
        SummaryPage page2 = harness.query(SummaryQuery.builder()
                                                      .having(Condition.gt("orderCount", threshold))
                                                      .orderByAggregate("totalAmount", SortDirection.DESC)
                                                      .limit(10)
                                                      .cursor(page1.cursor())
                                                      .build());
        assertThat(page2.rows()).hasSize(10);
    }

    @Test
    public void havingWithBetweenAndOr() {
        ProjectionHarness harness = buildHarness(20, 10);
        int threshold = 9;
        SummaryPage between = harness.query(SummaryQuery.builder()
                                                      .having(Condition.between("orderCount",
                                                                                threshold - 1,
                                                                                threshold + 1))
                                                      .limit(50)
                                                      .build());
        assertThat(between.rows()).hasSize(20);

        SummaryPage orPage = harness.query(SummaryQuery.builder()
                                                      .having(Condition.gt("orderCount", threshold)
                                                                       .or(Condition.lt("orderCount", 5)))
                                                      .limit(50)
                                                      .build());
        assertThat(orPage.rows()).isNotEmpty();
    }

    @Test
    public void outerJoinOrphanCustomerLeftRow() {
        JoinProjectionHarness leftHarness = joinHarness(JoinType.LEFT);
        leftHarness.applyRecord(StreamRecordDecoder.insert("Customer", "c_orphan",
            customer("c_orphan", "Orphan", "EU")));
        List<Map<String, AttributeValue>> rows = leftHarness.getJoinRows("c_orphan");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("orderId").s()).isEqualTo(JoinProjectionApplyEngine.LEFT_ONLY_SORT_KEY);
    }

    @Test
    public void outerJoinOrphanOrderRightRow() {
        JoinProjectionHarness rightHarness = joinHarness(JoinType.RIGHT);
        Map<String, Object> order = order("c_nonexistent", "o_orphan", 99, "Unknown", "US");
        rightHarness.applyRecord(StreamRecordDecoder.insert("Order", "c_nonexistent#o_orphan", order));
        List<Map<String, AttributeValue>> rows = rightHarness.getJoinRows("c_nonexistent");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("amount").n()).isEqualTo("99");
    }

    @Test
    public void joinPaginationPage2() {
        JoinProjectionHarness harness = joinHarness(JoinType.INNER);
        seedCustomerAndOrders(harness, "c1", "Customer-1", "US", 25);
        JoinProjectionHarness.JoinPage page1 = harness.queryPage("c1", 10, null);
        assertThat(page1.rows()).hasSize(10);
        JoinProjectionHarness.JoinPage page2 = harness.queryPage("c1", 10, page1.cursor());
        assertThat(page2.rows()).hasSize(10);
    }

    @Test
    public void batchGetFiveCustomerSummaries() {
        ProjectionHarness harness = buildHarness(5, 3);
        for (int c = 1; c <= 5; c++) {
            assertThat(harness.getAggregates(Collections.singletonMap("customerId", "c" + c)))
                .containsKey("orderCount");
        }
    }

    @Test
    public void customerModifyFanoutUpdatesJoinRegion() {
        JoinProjectionHarness harness = joinHarness(JoinType.INNER);
        harness.applyRecord(StreamRecordDecoder.insert("Customer", "c1", customer("c1", "Customer-1", "US")));
        harness.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order("c1", "o1", 10, "Customer-1", "US")));
        harness.applyRecord(StreamRecordDecoder.modify("Customer", "c1",
            customer("c1", "Customer-1", "US"),
            customer("c1", "Customer1Modified", "APAC")));
        assertThat(harness.getJoinRows("c1").get(0).get("region").s()).isEqualTo("APAC");
    }

    private static ProjectionHarness buildHarness(int customerCount, int ordersPerCustomer) {
        ProjectionHarness harness = ProjectionHarness.of(ProjectionDynamoDbBenchmarkAccess.BY_CUSTOMER_SPEC);
        for (int c = 1; c <= customerCount; c++) {
            String customerId = "c" + c;
            String region = (c % 2 == 0) ? "EU" : "US";
            String name = "Customer-" + c;
            for (int o = 1; o <= ordersPerCustomer; o++) {
                harness.applyRecord(StreamRecordDecoder.insert("Order", customerId + "#o" + o,
                    order(customerId, "o" + o, (o % 100) + 1, name, region)));
            }
        }
        return harness;
    }

    private static JoinProjectionHarness joinHarness(JoinType joinType) {
        JoinProjectionSpec spec = JoinProjections.builder("CustomersOrdersJoin")
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
        return JoinProjectionHarness.of(spec);
    }

    private static void seedCustomerAndOrders(JoinProjectionHarness harness,
                                              String customerId,
                                              String name,
                                              String region,
                                              int orderCount) {
        harness.applyRecord(StreamRecordDecoder.insert("Customer", customerId, customer(customerId, name, region)));
        for (int o = 1; o <= orderCount; o++) {
            harness.applyRecord(StreamRecordDecoder.insert("Order", customerId + "#o" + o,
                order(customerId, "o" + o, o, name, region)));
        }
    }

    private static Map<String, Object> customer(String id, String name, String region) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("customerId", id);
        customer.put("name", name);
        customer.put("region", region);
        customer.put("_v", VersionGenerator.next());
        return customer;
    }

    private static Map<String, Object> order(String customerId,
                                             String orderId,
                                             int amount,
                                             String name,
                                             String region) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("customerId", customerId);
        order.put("orderId", orderId);
        order.put("amount", amount);
        order.put("name", name);
        order.put("region", region);
        order.put("_v", VersionGenerator.next());
        return order;
    }
}
