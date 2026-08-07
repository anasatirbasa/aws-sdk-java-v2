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

import software.amazon.awssdk.annotations.SdkPublicApi;

/**
 * Entry-point helpers for declaring join projections ({@link JoinType#INNER} by default;
 * also {@link JoinType#LEFT}, {@link JoinType#RIGHT}, {@link JoinType#FULL}).
 *
 * <pre>{@code
 * JoinProjectionSpec customersOrders = JoinProjections.builder("CustomersOrdersJoin")
 *     .joinType(JoinType.INNER) // or LEFT / RIGHT / FULL
 *     .leftEntityType("Customer")
 *     .rightEntityType("Order")
 *     .leftJoinAttribute("customerId")
 *     .rightJoinAttribute("customerId")
 *     .rightSortKeyAttribute("orderId")
 *     .leftFields("name", "region")
 *     .rightFields("orderId", "amount")
 *     .target(TargetTable.of("CustomersOrdersJoin", "customerId", "orderId"))
 *     .build();
 * }</pre>
 *
 * <p>Apply with {@link JoinProjectionApplicator} (DynamoDB) or {@link JoinProjectionHarness}
 * (in-memory). See {@code PROJECTION_PLAYBOOK.md}.
 */
@SdkPublicApi
public final class JoinProjections {

    private JoinProjections() {
    }

    public static JoinProjectionSpec.Builder builder(String name) {
        return JoinProjectionSpec.builder().name(name);
    }
}
