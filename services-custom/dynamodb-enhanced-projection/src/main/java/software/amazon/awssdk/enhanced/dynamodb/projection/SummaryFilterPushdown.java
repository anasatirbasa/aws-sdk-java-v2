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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.enhanced.dynamodb.query.condition.Condition;
import software.amazon.awssdk.enhanced.dynamodb.query.condition.ConditionEvaluator;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Pushes simple {@link Condition} trees to DynamoDB {@code FilterExpression}.
 */
@SdkInternalApi
final class SummaryFilterPushdown {

    private SummaryFilterPushdown() {
    }

    static Optional<Pushdown> tryPushdown(Condition condition) {
        return ConditionEvaluator.tryFilterPushdown(condition).map(SummaryFilterPushdown::toPushdown);
    }

    private static Pushdown toPushdown(ConditionEvaluator.FilterPushdown pushdown) {
        Map<String, AttributeValue> values = new HashMap<>();
        for (Map.Entry<String, Object> e : pushdown.expressionAttributeValues().entrySet()) {
            values.put(e.getKey(), toAttributeValue(e.getValue()));
        }
        return new Pushdown(pushdown.filterExpression(),
                            pushdown.expressionAttributeNames(),
                            values);
    }

    private static AttributeValue toAttributeValue(Object value) {
        if (value == null) {
            return AttributeValue.builder().nul(true).build();
        }
        if (value instanceof Number) {
            return AttributeValue.builder().n(value.toString()).build();
        }
        if (value instanceof Boolean) {
            return AttributeValue.builder().bool((Boolean) value).build();
        }
        return AttributeValue.builder().s(String.valueOf(value)).build();
    }

    static final class Pushdown {
        private final String filterExpression;
        private final Map<String, String> expressionAttributeNames;
        private final Map<String, AttributeValue> expressionAttributeValues;

        Pushdown(String filterExpression,
                 Map<String, String> expressionAttributeNames,
                 Map<String, AttributeValue> expressionAttributeValues) {
            this.filterExpression = filterExpression;
            this.expressionAttributeNames = expressionAttributeNames;
            this.expressionAttributeValues = expressionAttributeValues;
        }

        String filterExpression() {
            return filterExpression;
        }

        Map<String, String> expressionAttributeNames() {
            return expressionAttributeNames;
        }

        Map<String, AttributeValue> expressionAttributeValues() {
            return expressionAttributeValues;
        }
    }
}
