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
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.projection.AggregateDefinition;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjections;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinType;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.Projections;
import software.amazon.awssdk.enhanced.dynamodb.projection.TargetTable;

public class BenchmarkBulkSeederTest {

    @Test
    public void fastBulkSeedMatchesStreamSimulationForSmallDataset() {
        int customers = 5;
        int ordersPerCustomer = 10;

        ProjectionHarness streamByCustomer = ProjectionHarness.of(ProjectionDynamoDbBenchmarkAccess.BY_CUSTOMER_SPEC);
        ProjectionHarness streamByRegion = ProjectionHarness.of(regionSpec());
        ProjectionHarness streamGlobal = ProjectionHarness.of(globalSpec());
        Map<JoinType, JoinProjectionHarness> streamJoins = joinHarnesses();

        BenchmarkBulkSeeder.SeedContext streamCtx = BenchmarkBulkSeeder.newContext(
            customers, ordersPerCustomer, 2, false, false,
            streamByCustomer, streamByRegion, streamGlobal, streamJoins);
        BenchmarkBulkSeeder.seedMainDatasetStreamSimulation(streamCtx);
        BenchmarkBulkSeeder.seedExtensionsStreamSimulation(streamCtx);

        ProjectionHarness bulkByCustomer = ProjectionHarness.of(ProjectionDynamoDbBenchmarkAccess.BY_CUSTOMER_SPEC);
        ProjectionHarness bulkByRegion = ProjectionHarness.of(regionSpec());
        ProjectionHarness bulkGlobal = ProjectionHarness.of(globalSpec());
        Map<JoinType, JoinProjectionHarness> bulkJoins = joinHarnesses();

        BenchmarkBulkSeeder.SeedContext bulkCtx = BenchmarkBulkSeeder.newContext(
            customers, ordersPerCustomer, 2, false, true,
            bulkByCustomer, bulkByRegion, bulkGlobal, bulkJoins);
        BenchmarkBulkSeeder.seedMainDataset(bulkCtx);
        BenchmarkBulkSeeder.seedExtensions(bulkCtx);

        for (int c = 1; c <= customers; c++) {
            String customerId = "c" + c;
            Map<String, Number> streamAggs = streamByCustomer.getAggregates(
                Collections.singletonMap("customerId", customerId));
            Map<String, Number> bulkAggs = bulkByCustomer.getAggregates(
                Collections.singletonMap("customerId", customerId));
            assertNumericMapsEqual(bulkAggs, streamAggs);
        }

        assertThat(bulkJoins.get(JoinType.INNER).getJoinRows("c1")).hasSize(
            streamJoins.get(JoinType.INNER).getJoinRows("c1").size());
        assertThat(bulkJoins.get(JoinType.INNER).getJoinRows("c1").get(0).get("region").s())
            .isEqualTo("APAC");
        assertThat(bulkJoins.get(JoinType.LEFT).getJoinRows(BenchmarkBulkSeeder.ORPHAN_CUSTOMER_ID)).hasSize(1);
    }

    private static void assertNumericMapsEqual(Map<String, Number> actual, Map<String, Number> expected) {
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        for (String key : expected.keySet()) {
            assertThat(actual.get(key).doubleValue()).isEqualTo(expected.get(key).doubleValue());
        }
    }

    private static Map<JoinType, JoinProjectionHarness> joinHarnesses() {
        Map<JoinType, JoinProjectionHarness> joins = new EnumMap<>(JoinType.class);
        for (JoinType type : JoinType.values()) {
            joins.put(type, JoinProjectionHarness.of(
                JoinProjections.builder("CustomersOrdersJoin")
                               .joinType(type)
                               .leftEntityType("Customer")
                               .rightEntityType("Order")
                               .leftJoinAttribute("customerId")
                               .rightJoinAttribute("customerId")
                               .rightSortKeyAttribute("orderId")
                               .leftFields("name", "region")
                               .rightFields("orderId", "amount")
                               .target(TargetTable.of("CustomersOrdersJoin", "customerId", "orderId"))
                               .build()));
        }
        return joins;
    }

    private static software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionSpec regionSpec() {
        return Projections.builder("OrdersByCustomerRegion")
                          .sourceEntityType("Order")
                          .groupBy("customerId", "region")
                          .target(TargetTable.of("OrdersByCustomerRegion", "customerId", "region"))
                          .field("orderCount", AggregateDefinition.count())
                          .field("totalAmount", AggregateDefinition.sum("amount"))
                          .build();
    }

    private static software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionSpec globalSpec() {
        return Projections.builder("OrdersGlobal")
                          .sourceEntityType("Order")
                          .groupBy()
                          .target(TargetTable.of("OrdersGlobal", "pk"))
                          .field("totalOrders", AggregateDefinition.count())
                          .field("totalRevenue", AggregateDefinition.sum("amount"))
                          .field("minAmount", AggregateDefinition.min("amount"))
                          .field("maxAmount", AggregateDefinition.max("amount"))
                          .build();
    }
}
