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
import software.amazon.awssdk.annotations.SdkProtectedApi;

/**
 * Delta / contribution math for projection aggregates.
 */
@SdkProtectedApi
final class AggregateDeltaMath {

    private AggregateDeltaMath() {
    }

    static boolean matches(AggregateDefinition node, Map<String, Object> item) {
        if (item == null) {
            return false;
        }
        return node.where().map(p -> p.test(item)).orElse(true);
    }

    static BigDecimal numericContribution(AggregateDefinition node, Map<String, Object> item) {
        if (!matches(node, item)) {
            return null;
        }
        String field = node.field().orElse(null);
        if (field == null) {
            return null;
        }
        return ProjectionPredicate.asNumber(item.get(field));
    }

    /**
     * Additive delta for COUNT, SUM, or AVG shadow sum/count.
     * For AVG, {@code shadow} selects which shadow attribute the delta applies to.
     */
    static Number computeAdditiveDelta(AggregateDefinition node,
                                       NormalizedRecord.EventName eventName,
                                       Map<String, Object> prev,
                                       Map<String, Object> next,
                                       AvgShadow shadow) {
        if (node.aggregationFunction() == AggregateDefinition.AggregationFunction.COUNT
            || (node.aggregationFunction() == AggregateDefinition.AggregationFunction.AVG
                && shadow == AvgShadow.COUNT)) {
            boolean wherePrev = matches(node, prev);
            boolean whereNext = matches(node, next);
            switch (eventName) {
                case INSERT:
                    return whereNext ? 1 : 0;
                case MODIFY:
                    return (whereNext ? 1 : 0) - (wherePrev ? 1 : 0);
                case REMOVE:
                    return wherePrev ? -1 : 0;
                default:
                    throw new IllegalStateException("unknown event: " + eventName);
            }
        }

        // SUM or AVG sum shadow
        BigDecimal prevValue = numericContribution(node, prev);
        BigDecimal nextValue = numericContribution(node, next);
        BigDecimal p = prevValue == null ? BigDecimal.ZERO : prevValue;
        BigDecimal n = nextValue == null ? BigDecimal.ZERO : nextValue;
        switch (eventName) {
            case INSERT:
                return n;
            case MODIFY:
                return n.subtract(p);
            case REMOVE:
                return p.negate();
            default:
                throw new IllegalStateException("unknown event: " + eventName);
        }
    }

    static boolean isZero(Number n) {
        return n == null || new BigDecimal(n.toString()).compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * Whether this event may invalidate a stored MIN/MAX (requires source recompute).
     */
    static boolean needsExtremeRecompute(AggregateDefinition node,
                                         NormalizedRecord.EventName eventName,
                                         Map<String, Object> prev,
                                         Map<String, Object> next) {
        BigDecimal prevContrib = numericContribution(node, prev);
        BigDecimal nextContrib = numericContribution(node, next);
        switch (eventName) {
            case INSERT:
                return false;
            case REMOVE:
                return prevContrib != null;
            case MODIFY:
                if (prevContrib == null) {
                    return false;
                }
                if (nextContrib == null) {
                    return true;
                }
                return prevContrib.compareTo(nextContrib) != 0;
            default:
                throw new IllegalStateException("unknown event: " + eventName);
        }
    }

    /**
     * Candidate value for a conditional MIN/MAX SET when recompute is not required.
     * Returns null when there is no new extreme candidate from this event alone.
     */
    static BigDecimal extremeCandidate(AggregateDefinition node,
                                   NormalizedRecord.EventName eventName,
                                   Map<String, Object> prev,
                                   Map<String, Object> next) {
        if (needsExtremeRecompute(node, eventName, prev, next)) {
            return null;
        }
        return numericContribution(node, next);
    }

    enum AvgShadow {
        SUM,
        COUNT
    }
}
