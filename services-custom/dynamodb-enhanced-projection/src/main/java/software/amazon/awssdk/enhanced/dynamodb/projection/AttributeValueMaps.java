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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.awssdk.annotations.SdkProtectedApi;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Converts between plain Java maps and DynamoDB {@link AttributeValue} maps.
 */
@SdkProtectedApi
final class AttributeValueMaps {

    private AttributeValueMaps() {
    }

    static Map<String, AttributeValue> toAttributeMap(Map<String, Object> item) {
        Map<String, AttributeValue> out = new LinkedHashMap<>();
        if (item == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : item.entrySet()) {
            out.put(e.getKey(), toAttributeValue(e.getValue()));
        }
        return out;
    }

    static Map<String, Object> fromAttributeMap(Map<String, AttributeValue> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (item == null) {
            return out;
        }
        for (Map.Entry<String, AttributeValue> e : item.entrySet()) {
            out.put(e.getKey(), fromAttributeValue(e.getValue()));
        }
        return out;
    }

    static AttributeValue toAttributeValue(Object value) {
        if (value == null) {
            return AttributeValue.builder().nul(true).build();
        }
        if (value instanceof AttributeValue) {
            return (AttributeValue) value;
        }
        if (value instanceof String) {
            return AttributeValue.builder().s((String) value).build();
        }
        if (value instanceof Number) {
            return AttributeValue.builder().n(new BigDecimal(value.toString()).toPlainString()).build();
        }
        if (value instanceof Boolean) {
            return AttributeValue.builder().bool((Boolean) value).build();
        }
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return AttributeValue.builder().m(toAttributeMap(map)).build();
        }
        throw new ProjectionException("unsupported attribute type: " + value.getClass().getName());
    }

    static Object fromAttributeValue(AttributeValue value) {
        if (value == null || Boolean.TRUE.equals(value.nul())) {
            return null;
        }
        if (value.s() != null) {
            return value.s();
        }
        if (value.n() != null) {
            return new BigDecimal(value.n());
        }
        if (value.bool() != null) {
            return value.bool();
        }
        if (value.m() != null) {
            return fromAttributeMap(value.m());
        }
        return value.toString();
    }
}
