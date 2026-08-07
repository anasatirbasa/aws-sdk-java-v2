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
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinApplyOutcome;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionApplicator;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjectionSpec;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinProjections;
import software.amazon.awssdk.enhanced.dynamodb.projection.JoinType;
import software.amazon.awssdk.enhanced.dynamodb.projection.StreamRecordDecoder;
import software.amazon.awssdk.enhanced.dynamodb.projection.TargetTable;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

public class JoinProjectionApplicatorIT extends ProjectionLocalDynamoDbTestBase {

    private static final String TABLE = "CustomersOrdersJoinApplicatorIT";

    private JoinProjectionApplicator applicator;

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

        applicator = JoinProjectionApplicator.builder()
            .client(client)
            .projection(spec)
            .parentResolver(key -> null)
            .batchWrites(true)
            .build();
    }

    @Test
    void orderInsertCreatesJoinRowWithEmbeddedParentFields() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("customerId", "c1");
        order.put("orderId", "o1");
        order.put("amount", 99);
        order.put("name", "Alice");
        order.put("region", "EU");

        JoinApplyOutcome outcome =
            applicator.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order));
        assertThat(outcome.kind()).isEqualTo(JoinApplyOutcome.Kind.WRITES);

        List<Map<String, AttributeValue>> rows = client.query(QueryRequest.builder()
                                                                          .tableName(TABLE)
                                                                          .keyConditionExpression("#pk = :pk")
                                                                          .expressionAttributeNames(
                                                                              Collections.singletonMap("#pk",
                                                                                                     "customerId"))
                                                                          .expressionAttributeValues(
                                                                              Collections.singletonMap(":pk",
                                                                                  AttributeValue.builder()
                                                                                                .s("c1")
                                                                                                .build()))
                                                                          .build()).items();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name").s()).isEqualTo("Alice");
        assertThat(rows.get(0).get("amount").n()).isEqualTo("99");
    }

    @Test
    void customerModifyUpdatesJoinRowLeftFields() {
        applicator.applyRecord(StreamRecordDecoder.insert("Customer", "c1", customer("c1", "Alice", "EU")));
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("customerId", "c1");
        order.put("orderId", "o1");
        order.put("amount", 10);
        order.put("name", "Alice");
        order.put("region", "EU");
        applicator.applyRecord(StreamRecordDecoder.insert("Order", "c1#o1", order));

        Map<String, Object> prev = customer("c1", "Alice", "EU");
        Map<String, Object> next = customer("c1", "AliceModified", "APAC");
        applicator.applyRecord(StreamRecordDecoder.modify("Customer", "c1", prev, next));

        List<Map<String, AttributeValue>> rows = client.query(QueryRequest.builder()
                                                                          .tableName(TABLE)
                                                                          .keyConditionExpression("#pk = :pk")
                                                                          .expressionAttributeNames(
                                                                              Collections.singletonMap("#pk",
                                                                                                     "customerId"))
                                                                          .expressionAttributeValues(
                                                                              Collections.singletonMap(":pk",
                                                                                  AttributeValue.builder()
                                                                                                .s("c1")
                                                                                                .build()))
                                                                          .build()).items();
        assertThat(rows.get(0).get("region").s()).isEqualTo("APAC");
    }

    private static Map<String, Object> customer(String id, String name, String region) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("customerId", id);
        customer.put("name", name);
        customer.put("region", region);
        return customer;
    }
}
