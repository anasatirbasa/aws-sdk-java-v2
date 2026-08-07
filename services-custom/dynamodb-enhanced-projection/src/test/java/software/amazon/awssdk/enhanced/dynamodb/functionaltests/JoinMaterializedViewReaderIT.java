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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinMaterializedViewReader;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionApplicator;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionHarness;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionSpec;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjections;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinType;
import software.amazon.awssdk.enhanced.dynamodb.projection.StreamRecordDecoder;
import software.amazon.awssdk.enhanced.dynamodb.projection.TargetTable;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

public class JoinMaterializedViewReaderIT extends ProjectionLocalDynamoDbTestBase {

    private static final String TABLE = "CustomersOrdersJoinReaderIT";

    private JoinMaterializedViewReader reader;

    @BeforeEach
    void setUp() {
        recreateTable(
            TABLE,
            Arrays.asList(
                KeySchemaElement.builder().attributeName("customerId").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("orderId").keyType(KeyType.RANGE).build()),
            Arrays.asList(
                AttributeDefinition.builder().attributeName("customerId")
                                 .attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("orderId")
                                 .attributeType(ScalarAttributeType.S).build()));

        JoinProjectionSpec spec = JoinProjections.builder("CustomersOrdersJoin")
            .joinType(JoinType.INNER)
            .leftEntityType("Customer")
            .rightEntityType("Order")
            .leftJoinAttribute("customerId")
            .rightJoinAttribute("customerId")
            .rightSortKeyAttribute("orderId")
            .leftFields("name", "region")
            .rightFields("orderId", "amount")
            .target(TargetTable.of(TABLE, "customerId", "orderId"))
            .build();

        JoinProjectionApplicator applicator = JoinProjectionApplicator.builder()
            .client(client)
            .projection(spec)
            .parentResolver(key -> null)
            .batchWrites(true)
            .build();

        for (int o = 1; o <= 25; o++) {
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("customerId", "c1");
            order.put("orderId", "o" + o);
            order.put("amount", o);
            order.put("name", "Customer-1");
            order.put("region", "US");
            applicator.applyRecord(StreamRecordDecoder.insert("Order", "c1#o" + o, order));
        }

        reader = JoinMaterializedViewReader.builder()
            .client(client)
            .projection(spec)
            .tableName(TABLE)
            .consistentRead(true)
            .build();
    }

    @Test
    void queryPageReturnsSecondPage() {
        JoinProjectionHarness.JoinPage page1 = reader.queryPage("c1", 10, null);
        assertThat(page1.rows()).hasSize(10);
        assertThat(page1.cursor()).isNotNull();

        JoinProjectionHarness.JoinPage page2 = reader.queryPage("c1", 10, page1.cursor());
        assertThat(page2.rows()).hasSize(10);
    }
}
