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

package software.amazon.awssdk.enhanced.dynamodb.functionaltests;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.enhanced.dynamodb.projection.AggregateDefinition;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionSpec;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjections;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinType;
import software.amazon.awssdk.enhanced.dynamodb.projection.ProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.Projections;
import software.amazon.awssdk.enhanced.dynamodb.projection.TargetTable;

/**
 * Guards join/aggregate seed regressions (S11 gate at reduced scale; optional 1000×1000 gate).
 */
public class ProjectionBenchmarkSeedPerfTest {

    @Test
    @Timeout(60)
    public void bulkSeed200x200CompletesUnderTimeout() {
        int customers = 200;
        int ordersPerCustomer = 200;
        BenchmarkBulkSeeder.SeedContext ctx = newSeedContext(customers, ordersPerCustomer);
        long ms = BenchmarkBulkSeeder.seedMainDataset(ctx);
        assertThat(ms).isLessThan(60_000L);
        assertThat(ctx.joinHarnesses.get(JoinType.INNER).getJoinRows("c1")).isNotEmpty();
    }

    @Test
    @Timeout(600)
    @EnabledIfEnvironmentVariable(named = "BENCHMARK_SEED_PERF_1000", matches = "true")
    public void bulkSeed1000x1000CompletesUnderTenMinutes() {
        int customers = 1000;
        int ordersPerCustomer = 1000;
        BenchmarkBulkSeeder.SeedContext ctx = newSeedContext(customers, ordersPerCustomer);
        long ms = BenchmarkBulkSeeder.seedMainDataset(ctx);
        System.out.printf("S11 bulk seed 1000×1000 buildMs=%d%n", ms);
        assertThat(ms).isLessThan(600_000L);
        assertThat(ctx.joinHarnesses.get(JoinType.INNER).getJoinRows("c1")).hasSize(ordersPerCustomer);
    }

    private static BenchmarkBulkSeeder.SeedContext newSeedContext(int customers, int ordersPerCustomer) {
        EnumMap<JoinType, JoinProjectionHarness> joins = new EnumMap<>(JoinType.class);
        for (JoinType type : JoinType.values()) {
            joins.put(type, JoinProjectionHarness.of(joinSpec(type)));
        }
        ProjectionHarness byCustomer = ProjectionHarness.of(ProjectionDynamoDbBenchmarkAccess.BY_CUSTOMER_SPEC);
        ProjectionHarness byRegion = ProjectionHarness.of(
            Projections.builder("OrdersByCustomerRegion")
                       .sourceEntityType("Order")
                       .groupBy("customerId", "region")
                       .target(TargetTable.of("OrdersByCustomerRegion", "customerId", "region"))
                       .field("orderCount", AggregateDefinition.count())
                       .build());
        ProjectionHarness global = ProjectionHarness.of(
            Projections.builder("OrdersGlobal")
                       .sourceEntityType("Order")
                       .groupBy()
                       .target(TargetTable.of("OrdersGlobal", "pk"))
                       .field("totalOrders", AggregateDefinition.count())
                       .build());
        return BenchmarkBulkSeeder.newContext(
            customers, ordersPerCustomer, Runtime.getRuntime().availableProcessors(), false, true,
            byCustomer, byRegion, global, joins);
    }

    private static JoinProjectionSpec joinSpec(JoinType joinType) {
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
}
