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

import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.utils.Validate;

/**
 * Declarative aggregate primitive for stream projections.
 *
 * <ul>
 *   <li>{@link #count} / {@link #sum} — additive DynamoDB {@code ADD} deltas</li>
 *   <li>{@link #avg} — maintains shadow sum/count and a stored avg attribute</li>
 *   <li>{@link #min} / {@link #max} — conditional extremes; recompute from source when
 *       an invalidate event may remove the current extreme</li>
 * </ul>
 */
@SdkPublicApi
public final class AggregateDefinition {

    /**
     * Aggregation function, aligned in naming with Enhanced Queries' AggregationFunction.
     */
    public enum AggregationFunction {
        COUNT,
        SUM,
        AVG,
        MIN,
        MAX
    }

    private final AggregationFunction aggregationFunction;
    private final String field;
    private final ProjectionPredicate where;

    private AggregateDefinition(AggregationFunction aggregationFunction,
                                String field,
                                ProjectionPredicate where) {
        this.aggregationFunction = Validate.paramNotNull(aggregationFunction, "aggregationFunction");
        this.field = field;
        this.where = where;
        if (aggregationFunction != AggregationFunction.COUNT) {
            Validate.paramNotBlank(field, "field");
        }
    }

    public static AggregateDefinition count() {
        return new AggregateDefinition(AggregationFunction.COUNT, null, null);
    }

    public static AggregateDefinition count(ProjectionPredicate where) {
        return new AggregateDefinition(AggregationFunction.COUNT, null, where);
    }

    public static AggregateDefinition sum(String field) {
        return new AggregateDefinition(AggregationFunction.SUM, field, null);
    }

    public static AggregateDefinition sum(String field, ProjectionPredicate where) {
        return new AggregateDefinition(AggregationFunction.SUM, field, where);
    }

    public static AggregateDefinition avg(String field) {
        return new AggregateDefinition(AggregationFunction.AVG, field, null);
    }

    public static AggregateDefinition avg(String field, ProjectionPredicate where) {
        return new AggregateDefinition(AggregationFunction.AVG, field, where);
    }

    public static AggregateDefinition min(String field) {
        return new AggregateDefinition(AggregationFunction.MIN, field, null);
    }

    public static AggregateDefinition min(String field, ProjectionPredicate where) {
        return new AggregateDefinition(AggregationFunction.MIN, field, where);
    }

    public static AggregateDefinition max(String field) {
        return new AggregateDefinition(AggregationFunction.MAX, field, null);
    }

    public static AggregateDefinition max(String field, ProjectionPredicate where) {
        return new AggregateDefinition(AggregationFunction.MAX, field, where);
    }

    public AggregationFunction aggregationFunction() {
        return aggregationFunction;
    }

    public Optional<String> field() {
        return Optional.ofNullable(field);
    }

    public Optional<ProjectionPredicate> where() {
        return Optional.ofNullable(where);
    }

    /** Shadow sum attribute used to maintain a stored {@link AggregationFunction#AVG}. */
    public static String avgSumAttr(String alias) {
        return "_avg_sum_" + alias;
    }

    /** Shadow count attribute used to maintain a stored {@link AggregationFunction#AVG}. */
    public static String avgCountAttr(String alias) {
        return "_avg_cnt_" + alias;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AggregateDefinition)) {
            return false;
        }
        AggregateDefinition that = (AggregateDefinition) o;
        return aggregationFunction == that.aggregationFunction
               && Objects.equals(field, that.field)
               && Objects.equals(where, that.where);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(aggregationFunction);
        result = 31 * result + Objects.hashCode(field);
        result = 31 * result + Objects.hashCode(where);
        return result;
    }
}
