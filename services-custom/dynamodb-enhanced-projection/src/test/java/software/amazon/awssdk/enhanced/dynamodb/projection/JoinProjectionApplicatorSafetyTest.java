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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class JoinProjectionApplicatorSafetyTest {
    @Test
    public void rejectsJoinKeyChanges() {
        JoinProjectionSpec spec = JoinProjections.builder("CustomerOrders")
                                                  .leftEntityType("Customer")
                                                  .rightEntityType("Order")
                                                  .leftJoinAttribute("customerId")
                                                  .rightJoinAttribute("customerId")
                                                  .rightSortKeyAttribute("orderId")
                                                  .target(TargetTable.of("CustomerOrders", "customerId", "orderId"))
                                                  .build();
        JoinProjectionApplicator applicator = JoinProjectionApplicator.builder()
            .client(Mockito.mock(DynamoDbClient.class))
            .projection(spec)
            .parentResolver(key -> null)
            .build();
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("customerId", "c1");
        previous.put("orderId", "o1");
        Map<String, Object> next = new LinkedHashMap<>(previous);
        next.put("customerId", "c2");

        assertThatThrownBy(() -> applicator.applyRecord(
            StreamRecordDecoder.modify("Order", "o1", previous, next)))
            .isInstanceOf(JoinKeyMutationException.class)
            .hasMessageContaining("customerId");
    }
}
