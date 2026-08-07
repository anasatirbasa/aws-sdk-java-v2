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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.utils.Validate;

/**
 * Result of planning a projection apply for one stream record.
 */
@SdkPublicApi
public abstract class ApplyOutcome {

    private ApplyOutcome() {
    }

    public abstract Kind kind();

    public enum Kind {
        APPLIED,
        SKIPPED
    }

    public static Applied applied(AppliedPlan plan) {
        return new Applied(plan);
    }

    public static Skipped skipped(SkipReason reason) {
        return new Skipped(reason);
    }

    public static final class Applied extends ApplyOutcome {
        private final AppliedPlan plan;

        private Applied(AppliedPlan plan) {
            this.plan = Validate.paramNotNull(plan, "plan");
        }

        @Override
        public Kind kind() {
            return Kind.APPLIED;
        }

        public AppliedPlan plan() {
            return plan;
        }

        /** Additive deltas (COUNT/SUM/AVG shadows) for observability and harness. */
        public Map<String, Number> deltas() {
            return plan.additiveDeltas();
        }

        public UpdateItemRequest request() {
            return plan.additiveRequest();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Applied)) {
                return false;
            }
            return Objects.equals(plan, ((Applied) o).plan);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(plan);
        }
    }

    public static final class Skipped extends ApplyOutcome {
        private final SkipReason reason;

        private Skipped(SkipReason reason) {
            this.reason = Validate.paramNotNull(reason, "reason");
        }

        @Override
        public Kind kind() {
            return Kind.SKIPPED;
        }

        public SkipReason reason() {
            return reason;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Skipped)) {
                return false;
            }
            return reason == ((Skipped) o).reason;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(reason);
        }
    }

    public enum SkipReason {
        /** Record entity type does not match the projection's source entity type. */
        WRONG_ENTITY_TYPE,
        /** Aggregate field values did not change for this event (zero deltas). */
        NO_AGGREGATE_FIELD_CHANGED,
        /**
         * Version-map / conditional check indicates this source version was already applied
         * (at-least-once replay), or a join write hit an ownership/version conflict.
         */
        ALREADY_APPLIED,
        /**
         * Join child event arrived but the left-side parent is not available yet
         * (INNER / LEFT).
         */
        MISSING_PARENT
    }

    /**
     * Planned writes for one successful apply.
     */
    @SdkPublicApi
    public static final class AppliedPlan {
        private final Map<String, AttributeValue> targetKey;
        private final String sourceItemKey;
        private final String effectiveVersion;
        private final Map<String, Number> additiveDeltas;
        private final Set<String> avgAliases;
        private final Map<String, ExtremeCandidate> extremeCandidates;
        private final Set<String> recomputeAliases;
        private final UpdateItemRequest additiveRequest;

        private AppliedPlan(Builder b) {
            this.targetKey = Collections.unmodifiableMap(new LinkedHashMap<>(b.targetKey));
            this.sourceItemKey = b.sourceItemKey;
            this.effectiveVersion = b.effectiveVersion;
            this.additiveDeltas = Collections.unmodifiableMap(new LinkedHashMap<>(b.additiveDeltas));
            this.avgAliases = Collections.unmodifiableSet(new LinkedHashSet<>(b.avgAliases));
            this.extremeCandidates = Collections.unmodifiableMap(new LinkedHashMap<>(b.extremeCandidates));
            this.recomputeAliases = Collections.unmodifiableSet(new LinkedHashSet<>(b.recomputeAliases));
            this.additiveRequest = b.additiveRequest;
        }

        public static Builder builder() {
            return new Builder();
        }

        public Map<String, AttributeValue> targetKey() {
            return targetKey;
        }

        public String sourceItemKey() {
            return sourceItemKey;
        }

        public String effectiveVersion() {
            return effectiveVersion;
        }

        public Map<String, Number> additiveDeltas() {
            return additiveDeltas;
        }

        public Set<String> avgAliases() {
            return avgAliases;
        }

        public Map<String, ExtremeCandidate> extremeCandidates() {
            return extremeCandidates;
        }

        public Set<String> recomputeAliases() {
            return recomputeAliases;
        }

        public UpdateItemRequest additiveRequest() {
            return additiveRequest;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AppliedPlan)) {
                return false;
            }
            AppliedPlan that = (AppliedPlan) o;
            return Objects.equals(targetKey, that.targetKey)
                   && Objects.equals(sourceItemKey, that.sourceItemKey)
                   && Objects.equals(effectiveVersion, that.effectiveVersion)
                   && Objects.equals(additiveDeltas, that.additiveDeltas)
                   && Objects.equals(avgAliases, that.avgAliases)
                   && Objects.equals(extremeCandidates, that.extremeCandidates)
                   && Objects.equals(recomputeAliases, that.recomputeAliases)
                   && Objects.equals(additiveRequest, that.additiveRequest);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(targetKey);
            result = 31 * result + Objects.hashCode(sourceItemKey);
            result = 31 * result + Objects.hashCode(effectiveVersion);
            result = 31 * result + Objects.hashCode(additiveDeltas);
            result = 31 * result + Objects.hashCode(avgAliases);
            result = 31 * result + Objects.hashCode(extremeCandidates);
            result = 31 * result + Objects.hashCode(recomputeAliases);
            result = 31 * result + Objects.hashCode(additiveRequest);
            return result;
        }

        public static final class Builder {
            private Map<String, AttributeValue> targetKey;
            private String sourceItemKey;
            private String effectiveVersion;
            private Map<String, Number> additiveDeltas = new LinkedHashMap<>();
            private Set<String> avgAliases = new LinkedHashSet<>();
            private Map<String, ExtremeCandidate> extremeCandidates = new LinkedHashMap<>();
            private Set<String> recomputeAliases = new LinkedHashSet<>();
            private UpdateItemRequest additiveRequest;

            public Builder targetKey(Map<String, AttributeValue> targetKey) {
                this.targetKey = targetKey;
                return this;
            }

            public Builder sourceItemKey(String sourceItemKey) {
                this.sourceItemKey = sourceItemKey;
                return this;
            }

            public Builder effectiveVersion(String effectiveVersion) {
                this.effectiveVersion = effectiveVersion;
                return this;
            }

            public Builder additiveDeltas(Map<String, Number> additiveDeltas) {
                this.additiveDeltas = additiveDeltas;
                return this;
            }

            public Builder avgAliases(Set<String> avgAliases) {
                this.avgAliases = avgAliases;
                return this;
            }

            public Builder extremeCandidates(Map<String, ExtremeCandidate> extremeCandidates) {
                this.extremeCandidates = extremeCandidates;
                return this;
            }

            public Builder recomputeAliases(Set<String> recomputeAliases) {
                this.recomputeAliases = recomputeAliases;
                return this;
            }

            public Builder additiveRequest(UpdateItemRequest additiveRequest) {
                this.additiveRequest = additiveRequest;
                return this;
            }

            public AppliedPlan build() {
                Validate.paramNotNull(targetKey, "targetKey");
                Validate.paramNotBlank(sourceItemKey, "sourceItemKey");
                Validate.paramNotBlank(effectiveVersion, "effectiveVersion");
                Validate.paramNotNull(additiveRequest, "additiveRequest");
                return new AppliedPlan(this);
            }
        }
    }

    /**
     * Conditional MIN/MAX candidate from a single stream event (no recompute).
     */
    @SdkPublicApi
    public static final class ExtremeCandidate {
        private final AggregateDefinition.AggregationFunction aggregationFunction;
        private final Number value;

        public ExtremeCandidate(AggregateDefinition.AggregationFunction aggregationFunction, Number value) {
            if (aggregationFunction != AggregateDefinition.AggregationFunction.MIN
                && aggregationFunction != AggregateDefinition.AggregationFunction.MAX) {
                throw new IllegalArgumentException("extreme candidate must be MIN or MAX");
            }
            this.aggregationFunction = aggregationFunction;
            this.value = Validate.paramNotNull(value, "value");
        }

        public AggregateDefinition.AggregationFunction aggregationFunction() {
            return aggregationFunction;
        }

        public Number value() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ExtremeCandidate)) {
                return false;
            }
            ExtremeCandidate that = (ExtremeCandidate) o;
            return aggregationFunction == that.aggregationFunction && Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(aggregationFunction);
            result = 31 * result + Objects.hashCode(value);
            return result;
        }
    }
}
