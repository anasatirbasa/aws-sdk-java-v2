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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.enhanced.dynamodb.query.condition.Condition;
import software.amazon.awssdk.enhanced.dynamodb.query.condition.ConditionEvaluator;
import software.amazon.awssdk.utils.Validate;

/**
 * Applies HAVING, ORDER BY, limit, and offset-cursor pagination to summary rows.
 */
@SdkPublicApi
public final class SummaryQueryEngine {

    private SummaryQueryEngine() {
    }

    public static SummaryPage execute(List<SummaryRow> rows, SummaryQuery query) {
        Validate.paramNotNull(rows, "rows");
        Validate.paramNotNull(query, "query");

        List<SummaryRow> filtered = new ArrayList<>();
        for (SummaryRow row : rows) {
            if (matchesHaving(row, query)) {
                filtered.add(row);
            }
        }

        if (!query.orderBy().isEmpty()) {
            filtered.sort(comparator(query.orderBy()));
        }

        int offset = ProjectionCursors.decodeOffset(query.cursor());
        if (offset < 0 || offset > filtered.size()) {
            throw new ProjectionException("invalid summary query cursor");
        }

        Integer limit = query.limit();
        int end = limit == null ? filtered.size() : Math.min(filtered.size(), offset + limit);
        List<SummaryRow> pageRows = new ArrayList<>(filtered.subList(offset, end));
        String next = end < filtered.size() ? ProjectionCursors.encodeOffset(end) : null;
        return new SummaryPage(pageRows, next);
    }

    static boolean matchesHaving(SummaryRow row, SummaryQuery query) {
        Map<String, Object> item = row.asHavingItem();
        Condition condition = query.havingCondition();
        if (condition != null) {
            return ConditionEvaluator.evaluate(condition, item);
        }
        ProjectionPredicate predicate = query.havingPredicate();
        if (predicate != null) {
            return predicate.test(item);
        }
        return true;
    }

    /**
     * Returns a copy of the query with HAVING cleared (used when filter was pushed to DynamoDB Scan).
     */
    static SummaryQuery withoutHaving(SummaryQuery query) {
        SummaryQuery.Builder builder = SummaryQuery.builder();
        for (SummaryOrderBy orderBy : query.orderBy()) {
            builder.orderBy(orderBy);
        }
        if (query.limit() != null) {
            builder.limit(query.limit());
        }
        if (query.cursor() != null) {
            builder.cursor(query.cursor());
        }
        return builder.build();
    }

    private static Comparator<SummaryRow> comparator(List<SummaryOrderBy> orderBy) {
        return (a, b) -> {
            for (SummaryOrderBy spec : orderBy) {
                int cmp = spec.direction() == SortDirection.DESC
                          ? compare(b, a, spec)
                          : compare(a, b, spec);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        };
    }

    private static int compare(SummaryRow a, SummaryRow b, SummaryOrderBy spec) {
        Object left = resolve(a, spec);
        Object right = resolve(b, spec);
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static Object resolve(SummaryRow row, SummaryOrderBy spec) {
        if (spec.byAggregate()) {
            return row.aggregates().get(spec.name());
        }
        Map<String, Object> key = row.key();
        if (key.containsKey(spec.name())) {
            return key.get(spec.name());
        }
        return row.aggregates().get(spec.name());
    }
}
