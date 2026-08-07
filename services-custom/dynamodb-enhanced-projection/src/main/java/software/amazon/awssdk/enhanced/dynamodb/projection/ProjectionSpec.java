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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.utils.Validate;

/**
 * Immutable declaration of a stream projection: source entity type, group-by dimensions,
 * target summary table, aggregate fields, and optional aggregate GSI for ORDER BY.
 */
@SdkPublicApi
public final class ProjectionSpec {

    private final String name;
    private final String generation;
    private final String sourceEntityType;
    private final List<String> groupBy;
    private final TargetTable target;
    private final Map<String, AggregateDefinition> fields;
    private final AggregateGsi aggregateGsi;
    private final List<String> carryForwardAttributes;

    private ProjectionSpec(Builder builder) {
        this.name = Validate.paramNotBlank(builder.name, "name");
        this.generation = Validate.paramNotBlank(builder.generation, "generation");
        this.sourceEntityType = Validate.paramNotBlank(builder.sourceEntityType, "sourceEntityType");
        this.groupBy = Collections.unmodifiableList(
            Validate.paramNotNull(builder.groupBy, "groupBy"));
        this.target = Validate.paramNotNull(builder.target, "target");
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(
            Validate.paramNotNull(builder.fields, "fields")));
        this.aggregateGsi = builder.aggregateGsi;
        this.carryForwardAttributes = Collections.unmodifiableList(
            new java.util.ArrayList<>(builder.carryForwardAttributes));
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("projection must declare at least one aggregate field");
        }
        if (groupBy.size() > 4) {
            throw new IllegalArgumentException("groupBy supports at most 4 dimensions");
        }
        if (groupBy.size() > 1 && target.sortKey() == null) {
            throw new IllegalArgumentException(
                "target.sortKey is required when groupBy has more than one dimension");
        }
        if (aggregateGsi != null && !fields.containsKey(aggregateGsi.sortKeyAggregateAlias())) {
            throw new IllegalArgumentException(
                "aggregateGsi.sortKeyAggregateAlias must match a declared field: "
                + aggregateGsi.sortKeyAggregateAlias());
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String name() {
        return name;
    }

    /**
     * Durable declaration generation. Change this when deploying an incompatible projection
     * definition so checkpoint state from the previous definition is never reused.
     */
    public String generation() {
        return generation;
    }

    public String sourceEntityType() {
        return sourceEntityType;
    }

    public List<String> groupBy() {
        return groupBy;
    }

    public TargetTable target() {
        return target;
    }

    public Map<String, AggregateDefinition> fields() {
        return fields;
    }

    public Optional<AggregateGsi> aggregateGsi() {
        return Optional.ofNullable(aggregateGsi);
    }

    /**
     * Source attributes copied onto each summary row at apply time (e.g. {@code region} for read-time filters).
     */
    public List<String> carryForwardAttributes() {
        return carryForwardAttributes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProjectionSpec)) {
            return false;
        }
        ProjectionSpec that = (ProjectionSpec) o;
        return Objects.equals(name, that.name)
               && Objects.equals(generation, that.generation)
               && Objects.equals(sourceEntityType, that.sourceEntityType)
               && Objects.equals(groupBy, that.groupBy)
               && Objects.equals(target, that.target)
               && Objects.equals(fields, that.fields)
               && Objects.equals(aggregateGsi, that.aggregateGsi)
               && Objects.equals(carryForwardAttributes, that.carryForwardAttributes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(name);
        result = 31 * result + Objects.hashCode(generation);
        result = 31 * result + Objects.hashCode(sourceEntityType);
        result = 31 * result + Objects.hashCode(groupBy);
        result = 31 * result + Objects.hashCode(target);
        result = 31 * result + Objects.hashCode(fields);
        result = 31 * result + Objects.hashCode(aggregateGsi);
        result = 31 * result + Objects.hashCode(carryForwardAttributes);
        return result;
    }

    public static final class Builder {
        private String name;
        private String generation = "1";
        private String sourceEntityType;
        private List<String> groupBy = Collections.emptyList();
        private TargetTable target;
        private Map<String, AggregateDefinition> fields = new LinkedHashMap<>();
        private AggregateGsi aggregateGsi;
        private List<String> carryForwardAttributes = new java.util.ArrayList<>();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** Sets the durable generation used to namespace stream checkpoint state. */
        public Builder generation(String generation) {
            this.generation = generation;
            return this;
        }

        public Builder sourceEntityType(String sourceEntityType) {
            this.sourceEntityType = sourceEntityType;
            return this;
        }

        public Builder groupBy(List<String> groupBy) {
            this.groupBy = groupBy;
            return this;
        }

        public Builder groupBy(String... groupBy) {
            this.groupBy = java.util.Arrays.asList(groupBy);
            return this;
        }

        public Builder target(TargetTable target) {
            this.target = target;
            return this;
        }

        public Builder field(String alias, AggregateDefinition aggregate) {
            this.fields.put(Validate.paramNotBlank(alias, "alias"),
                            Validate.paramNotNull(aggregate, "aggregate"));
            return this;
        }

        public Builder fields(Map<String, AggregateDefinition> fields) {
            this.fields = new LinkedHashMap<>(fields);
            return this;
        }

        public Builder aggregateGsi(AggregateGsi aggregateGsi) {
            this.aggregateGsi = aggregateGsi;
            return this;
        }

        public Builder carryForward(String... attributes) {
            this.carryForwardAttributes = java.util.Arrays.asList(attributes);
            return this;
        }

        public ProjectionSpec build() {
            return new ProjectionSpec(this);
        }
    }
}
