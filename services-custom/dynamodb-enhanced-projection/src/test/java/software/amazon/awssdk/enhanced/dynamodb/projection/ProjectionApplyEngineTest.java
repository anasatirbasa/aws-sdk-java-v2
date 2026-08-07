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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class ProjectionApplyEngineTest {

    @Test
    public void buildApplyRequestProducesAddAndVersionCondition() {
        ProjectionSpec spec = Projections.builder("OrdersByCustomer")
                                         .sourceEntityType("Order")
                                         .groupBy("customerId")
                                         .target(TargetTable.of("OrdersByCustomer", "customerId"))
                                         .field("orderCount", AggregateDefinition.count())
                                         .field("totalAmount", AggregateDefinition.sum("amount"))
                                         .build();

        Map<String, Object> order = new HashMap<>();
        order.put("customerId", "c1");
        order.put("amount", 25);
        NormalizedRecord record = StreamRecordDecoder.insert("Order", "c1#o1", order);

        ApplyOutcome outcome = ProjectionApplyEngine.buildApplyRequest(spec, record);
        assertThat(outcome.kind()).isEqualTo(ApplyOutcome.Kind.APPLIED);

        ApplyOutcome.Applied applied = (ApplyOutcome.Applied) outcome;
        UpdateItemRequest req = applied.request();
        assertThat(req.tableName()).isEqualTo("OrdersByCustomer");
        assertThat(req.key()).containsKey("customerId");
        assertThat(req.updateExpression()).startsWith("ADD ");
        assertThat(req.conditionExpression()).contains("#owner");
        assertThat(req.conditionExpression()).contains("#ventry");
        assertThat(req.expressionAttributeNames()).containsEntry("#owner", "_owner");
        assertThat(req.expressionAttributeNames().get("#ventry"))
            .isEqualTo(ProjectionApplyEngine.versionMapAttributeName("c1#o1"));
        assertThat(applied.deltas().get("orderCount").intValue()).isEqualTo(1);
        assertThat(applied.deltas().get("totalAmount").intValue()).isEqualTo(25);
    }

    @Test
    public void avgProducesShadowDeltas() {
        ProjectionSpec spec = Projections.builder("OrdersByCustomer")
                                         .sourceEntityType("Order")
                                         .groupBy("customerId")
                                         .target(TargetTable.of("OrdersByCustomer", "customerId"))
                                         .field("avgAmount", AggregateDefinition.avg("amount"))
                                         .build();
        Map<String, Object> order = new HashMap<>();
        order.put("customerId", "c1");
        order.put("amount", 40);
        ApplyOutcome.Applied applied = (ApplyOutcome.Applied)
            ProjectionApplyEngine.buildApplyRequest(spec, StreamRecordDecoder.insert("Order", "c1#o1", order));
        assertThat(applied.plan().avgAliases()).containsExactly("avgAmount");
        assertThat(applied.deltas().get(AggregateDefinition.avgSumAttr("avgAmount")).intValue()).isEqualTo(40);
        assertThat(applied.deltas().get(AggregateDefinition.avgCountAttr("avgAmount")).intValue()).isEqualTo(1);
    }

    @Test
    public void minInsertIsExtremeCandidateNotRecompute() {
        ProjectionSpec spec = Projections.builder("OrdersByCustomer")
                                         .sourceEntityType("Order")
                                         .groupBy("customerId")
                                         .target(TargetTable.of("OrdersByCustomer", "customerId"))
                                         .field("minAmount", AggregateDefinition.min("amount"))
                                         .build();
        Map<String, Object> order = new HashMap<>();
        order.put("customerId", "c1");
        order.put("amount", 10);
        ApplyOutcome.Applied applied = (ApplyOutcome.Applied)
            ProjectionApplyEngine.buildApplyRequest(spec, StreamRecordDecoder.insert("Order", "c1#o1", order));
        assertThat(applied.plan().recomputeAliases()).isEmpty();
        assertThat(applied.plan().extremeCandidates()).containsKey("minAmount");
        assertThat(applied.plan().extremeCandidates().get("minAmount").value().intValue()).isEqualTo(10);
    }

    @Test
    public void minRemoveRequiresRecompute() {
        ProjectionSpec spec = Projections.builder("OrdersByCustomer")
                                         .sourceEntityType("Order")
                                         .groupBy("customerId")
                                         .target(TargetTable.of("OrdersByCustomer", "customerId"))
                                         .field("minAmount", AggregateDefinition.min("amount"))
                                         .build();
        Map<String, Object> order = new HashMap<>();
        order.put("customerId", "c1");
        order.put("amount", 10);
        order.put("_v", "01A");
        ApplyOutcome.Applied applied = (ApplyOutcome.Applied)
            ProjectionApplyEngine.buildApplyRequest(spec, StreamRecordDecoder.remove("Order", "c1#o1", order));
        assertThat(applied.plan().recomputeAliases()).containsExactly("minAmount");
        assertThat(applied.plan().extremeCandidates()).isEmpty();
    }

    @Test
    public void wrongEntityTypeIsSkipped() {
        ProjectionSpec spec = Projections.builder("OrdersByCustomer")
                                         .sourceEntityType("Order")
                                         .groupBy("customerId")
                                         .target(TargetTable.of("OrdersByCustomer", "customerId"))
                                         .field("orderCount", AggregateDefinition.count())
                                         .build();
        Map<String, Object> item = new HashMap<>();
        item.put("customerId", "c1");
        NormalizedRecord record = StreamRecordDecoder.insert("Customer", "c1", item);
        ApplyOutcome outcome = ProjectionApplyEngine.buildApplyRequest(spec, record);
        assertThat(outcome.kind()).isEqualTo(ApplyOutcome.Kind.SKIPPED);
        assertThat(((ApplyOutcome.Skipped) outcome).reason())
            .isEqualTo(ApplyOutcome.SkipReason.WRONG_ENTITY_TYPE);
    }
}
