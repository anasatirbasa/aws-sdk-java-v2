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

/**
 * Closed-form aggregates for the synthetic A9 benchmark dataset ({@code amount = (orderIndex % 100) + 1}).
 */
final class BenchmarkBulkSeedMath {

    private BenchmarkBulkSeedMath() {
    }

    static int orderAmount(int orderIndex) {
        return (orderIndex % 100) + 1;
    }

    static String customerRegion(int customerIndex) {
        return (customerIndex % 2 == 0) ? "EU" : "US";
    }

    static OrderStats statsForOrders(int ordersPerCustomer) {
        long orderCount = ordersPerCustomer;
        long totalAmount = 0;
        int largeOrders = 0;
        long largeRevenue = 0;
        int minAmount = Integer.MAX_VALUE;
        int maxAmount = Integer.MIN_VALUE;
        for (int o = 1; o <= ordersPerCustomer; o++) {
            int amount = orderAmount(o);
            totalAmount += amount;
            if (amount >= 50) {
                largeOrders++;
                largeRevenue += amount;
            }
            minAmount = Math.min(minAmount, amount);
            maxAmount = Math.max(maxAmount, amount);
        }
        if (ordersPerCustomer == 0) {
            minAmount = 0;
            maxAmount = 0;
        }
        double avgAmount = orderCount == 0 ? 0.0 : totalAmount / (double) orderCount;
        return new OrderStats(orderCount, totalAmount, avgAmount, minAmount, maxAmount, largeOrders, largeRevenue);
    }

    static OrderStats globalStats(int customerCount, int ordersPerCustomer) {
        OrderStats perCustomer = statsForOrders(ordersPerCustomer);
        return new OrderStats(
            perCustomer.orderCount * customerCount,
            perCustomer.totalAmount * customerCount,
            perCustomer.avgAmount,
            perCustomer.minAmount,
            perCustomer.maxAmount,
            0,
            0);
    }

    static final class OrderStats {
        final long orderCount;
        final long totalAmount;
        final double avgAmount;
        final int minAmount;
        final int maxAmount;
        final int largeOrders;
        final long largeRevenue;

        OrderStats(long orderCount,
                   long totalAmount,
                   double avgAmount,
                   int minAmount,
                   int maxAmount,
                   int largeOrders,
                   long largeRevenue) {
            this.orderCount = orderCount;
            this.totalAmount = totalAmount;
            this.avgAmount = avgAmount;
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
            this.largeOrders = largeOrders;
            this.largeRevenue = largeRevenue;
        }
    }
}
