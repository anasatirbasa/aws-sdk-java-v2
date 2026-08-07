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
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.utils.Validate;

/**
 * Simple predicate used by filtered aggregates ({@code where} on count/sum).
 * PoC supports equality and numeric comparisons on top-level attributes.
 */
@SdkPublicApi
@FunctionalInterface
public interface ProjectionPredicate {

    boolean test(Map<String, Object> item);

    static ProjectionPredicate eq(String attribute, Object value) {
        Validate.paramNotBlank(attribute, "attribute");
        return item -> item != null && Objects.equals(item.get(attribute), value);
    }

    static ProjectionPredicate ne(String attribute, Object value) {
        Validate.paramNotBlank(attribute, "attribute");
        return item -> item != null && !Objects.equals(item.get(attribute), value);
    }

    static ProjectionPredicate gte(String attribute, Number value) {
        Validate.paramNotBlank(attribute, "attribute");
        Validate.paramNotNull(value, "value");
        return item -> {
            if (item == null) {
                return false;
            }
            BigDecimal n = asNumber(item.get(attribute));
            return n != null && n.compareTo(new BigDecimal(value.toString())) >= 0;
        };
    }

    static ProjectionPredicate gt(String attribute, Number value) {
        Validate.paramNotBlank(attribute, "attribute");
        Validate.paramNotNull(value, "value");
        return item -> {
            if (item == null) {
                return false;
            }
            BigDecimal n = asNumber(item.get(attribute));
            return n != null && n.compareTo(new BigDecimal(value.toString())) > 0;
        };
    }

    static ProjectionPredicate lte(String attribute, Number value) {
        Validate.paramNotBlank(attribute, "attribute");
        Validate.paramNotNull(value, "value");
        return item -> {
            if (item == null) {
                return false;
            }
            BigDecimal n = asNumber(item.get(attribute));
            return n != null && n.compareTo(new BigDecimal(value.toString())) <= 0;
        };
    }

    static ProjectionPredicate lt(String attribute, Number value) {
        Validate.paramNotBlank(attribute, "attribute");
        Validate.paramNotNull(value, "value");
        return item -> {
            if (item == null) {
                return false;
            }
            BigDecimal n = asNumber(item.get(attribute));
            return n != null && n.compareTo(new BigDecimal(value.toString())) < 0;
        };
    }

    static ProjectionPredicate and(ProjectionPredicate left, ProjectionPredicate right) {
        Validate.paramNotNull(left, "left");
        Validate.paramNotNull(right, "right");
        return item -> left.test(item) && right.test(item);
    }

    static BigDecimal asNumber(Object value) {
        if (value instanceof Number) {
            return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(value.toString());
        }
        if (value instanceof String) {
            try {
                return new BigDecimal((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
