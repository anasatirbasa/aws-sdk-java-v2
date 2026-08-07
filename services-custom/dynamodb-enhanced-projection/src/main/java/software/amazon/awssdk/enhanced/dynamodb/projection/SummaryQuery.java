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
import java.util.Collections;
import java.util.List;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.enhanced.dynamodb.query.condition.Condition;

/**
 * Read-time query over projected summary rows: HAVING, ORDER BY, limit, and opaque cursor.
 *
 * <p>HAVING accepts Enhanced Queries {@link Condition} for full operator parity, or legacy
 * {@link ProjectionPredicate} for simple filters.
 *
 * <p>Pagination modes:
 * <ul>
 *   <li>Offset cursor ({@link ProjectionCursors#encodeOffset}) after HAVING+ORDER BY</li>
 *   <li>LEK cursor ({@link ProjectionCursors#encodeExclusiveStartKey}) via
 *       {@link DynamoDbSummaryTableReader#scanPage} or {@link DynamoDbSummaryTableReader#queryByAggregateGsi}</li>
 * </ul>
 */
@SdkPublicApi
public final class SummaryQuery {

    private final Condition havingCondition;
    private final ProjectionPredicate havingPredicate;
    private final List<SummaryOrderBy> orderBy;
    private final Integer limit;
    private final String cursor;

    private SummaryQuery(Builder builder) {
        this.havingCondition = builder.havingCondition;
        this.havingPredicate = builder.havingPredicate;
        this.orderBy = Collections.unmodifiableList(new ArrayList<>(builder.orderBy));
        this.limit = builder.limit;
        this.cursor = builder.cursor;
        if (limit != null && limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Post-aggregation filter using Enhanced Queries condition operators.
     */
    public Condition havingCondition() {
        return havingCondition;
    }

    /**
     * Legacy post-aggregation filter (simple predicates only).
     */
    public ProjectionPredicate havingPredicate() {
        return havingPredicate;
    }

    /**
     * @deprecated use {@link #havingCondition()} or {@link #havingPredicate()}
     */
    @Deprecated
    public ProjectionPredicate having() {
        return havingPredicate;
    }

    public List<SummaryOrderBy> orderBy() {
        return orderBy;
    }

    public Integer limit() {
        return limit;
    }

    public String cursor() {
        return cursor;
    }

    public static final class Builder {
        private Condition havingCondition;
        private ProjectionPredicate havingPredicate;
        private final List<SummaryOrderBy> orderBy = new ArrayList<>();
        private Integer limit;
        private String cursor;

        public Builder having(Condition having) {
            this.havingCondition = having;
            return this;
        }

        public Builder having(ProjectionPredicate having) {
            this.havingPredicate = having;
            return this;
        }

        public Builder orderBy(SummaryOrderBy orderBy) {
            this.orderBy.add(orderBy);
            return this;
        }

        public Builder orderByAggregate(String aggregateAlias, SortDirection direction) {
            return orderBy(SummaryOrderBy.byAggregate(aggregateAlias, direction));
        }

        public Builder orderByKey(String keyAttribute, SortDirection direction) {
            return orderBy(SummaryOrderBy.byKey(keyAttribute, direction));
        }

        public Builder limit(Integer limit) {
            this.limit = limit;
            return this;
        }

        public Builder cursor(String cursor) {
            this.cursor = cursor;
            return this;
        }

        public SummaryQuery build() {
            return new SummaryQuery(this);
        }
    }
}
