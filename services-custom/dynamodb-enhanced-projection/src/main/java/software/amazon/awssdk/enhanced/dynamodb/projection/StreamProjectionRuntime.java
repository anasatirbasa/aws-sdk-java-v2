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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.utils.Validate;

/**
 * Ordered Streams runtime shared by every projection registered for a source stream.
 * Adapters should translate {@link ProjectionBatchResult#firstFailedItemIdentifier()} into their
 * platform's partial-batch response.
 */
@SdkPublicApi
public final class StreamProjectionRuntime {
    private final Map<String, List<ProjectionApplicator>> aggregatesBySource;
    private final Map<String, List<JoinRegistration>> joinsBySource;
    private final ProjectionRuntimeObserver observer;

    private StreamProjectionRuntime(Builder builder) {
        this.aggregatesBySource = new LinkedHashMap<>();
        for (Map.Entry<String, List<ProjectionApplicator>> entry : builder.aggregatesBySource.entrySet()) {
            this.aggregatesBySource.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        this.joinsBySource = new LinkedHashMap<>();
        for (Map.Entry<String, List<JoinRegistration>> entry : builder.joinsBySource.entrySet()) {
            this.joinsBySource.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        this.observer = builder.observer == null ? ProjectionRuntimeObserver.NO_OP : builder.observer;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ProjectionBatchResult handle(String sourceArn, List<Record> records) {
        Validate.paramNotBlank(sourceArn, "sourceArn");
        if (records == null || records.isEmpty()) {
            return ProjectionBatchResult.success();
        }
        for (Record record : records) {
            List<ProjectionApplicator> applicators = aggregatesBySource.get(sourceArn);
            List<JoinRegistration> joins = joinsBySource.get(sourceArn);
            if (applicators == null && joins == null) {
                ProjectionException error = new ProjectionException("no projection registered for source " + sourceArn);
                observer.onFailure(sourceArn, record.eventID(), error);
                return ProjectionBatchResult.failure(sequenceNumber(record));
            }
            try {
                if (applicators != null) {
                    for (ProjectionApplicator applicator : applicators) {
                        ApplyOutcome outcome = applicator.applyStreamRecord(record);
                        if (outcome.kind() == ApplyOutcome.Kind.APPLIED) {
                            observer.onApplied(applicator.projection().name(), record.eventID());
                        } else {
                            observer.onSkipped(applicator.projection().name(), record.eventID(),
                                               ((ApplyOutcome.Skipped) outcome).reason());
                        }
                    }
                }
                if (joins != null) {
                    for (JoinRegistration join : joins) {
                        JoinApplyOutcome outcome = join.applicator.applyStreamRecord(record, join.entityType);
                        if (outcome.kind() == JoinApplyOutcome.Kind.WRITES) {
                            observer.onApplied(join.applicator.projection().name(), record.eventID());
                        } else {
                            observer.onSkipped(join.applicator.projection().name(), record.eventID(),
                                               ((JoinApplyOutcome.Skipped) outcome).reason());
                        }
                    }
                }
            } catch (RuntimeException e) {
                observer.onFailure(sourceArn, record.eventID(), e);
                return ProjectionBatchResult.failure(sequenceNumber(record));
            }
        }
        return ProjectionBatchResult.success();
    }

    private static String sequenceNumber(Record record) {
        if (record.dynamodb() == null || record.dynamodb().sequenceNumber() == null) {
            return record.eventID();
        }
        return record.dynamodb().sequenceNumber();
    }

    public static final class Builder {
        private final Map<String, List<ProjectionApplicator>> aggregatesBySource = new LinkedHashMap<>();
        private final Map<String, List<JoinRegistration>> joinsBySource = new LinkedHashMap<>();
        private ProjectionRuntimeObserver observer;

        public Builder registerAggregate(String sourceArn, ProjectionApplicator applicator) {
            String validatedSource = Validate.paramNotBlank(sourceArn, "sourceArn");
            aggregatesBySource.computeIfAbsent(validatedSource, ignored -> new ArrayList<>())
                              .add(Validate.paramNotNull(applicator, "applicator"));
            return this;
        }

        public Builder observer(ProjectionRuntimeObserver observer) {
            this.observer = observer;
            return this;
        }

        public Builder registerJoin(String sourceArn, JoinProjectionApplicator applicator, String entityType) {
            String validatedSource = Validate.paramNotBlank(sourceArn, "sourceArn");
            joinsBySource.computeIfAbsent(validatedSource, ignored -> new ArrayList<>())
                         .add(new JoinRegistration(Validate.paramNotNull(applicator, "applicator"),
                                                   Validate.paramNotBlank(entityType, "entityType")));
            return this;
        }

        public StreamProjectionRuntime build() {
            if (aggregatesBySource.isEmpty() && joinsBySource.isEmpty()) {
                throw new IllegalArgumentException("at least one projection must be registered");
            }
            return new StreamProjectionRuntime(this);
        }
    }

    private static final class JoinRegistration {
        private final JoinProjectionApplicator applicator;
        private final String entityType;

        private JoinRegistration(JoinProjectionApplicator applicator, String entityType) {
            this.applicator = applicator;
            this.entityType = entityType;
        }
    }
}
