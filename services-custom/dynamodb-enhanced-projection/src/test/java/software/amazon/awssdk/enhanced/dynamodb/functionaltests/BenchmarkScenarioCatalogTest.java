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
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package software.amazon.awssdk.enhanced.dynamodb.functionaltests;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class BenchmarkScenarioCatalogTest {

    @Test
    public void loads35CanonicalScenarioKeys() {
        assertThat(BenchmarkScenarioCatalog.scenarioKeys()).hasSize(BenchmarkScenarioCatalog.expectedCount());
        assertThat(BenchmarkScenarioCatalog.scenarioKeys().get(0)).isEqualTo("single_customer_by_key");
        assertThat(BenchmarkScenarioCatalog.scenarioKeys().get(34)).isEqualTo("customer_modify_fanout_region");
    }

    @Test
    public void legacyJoinAliasesPresent() {
        assertThat(BenchmarkScenarioCatalog.legacyAliases())
            .containsEntry("join_all_orders_one_customer", "join_all_orders_one_customer_inner");
    }

    @Test
    public void metadataIsPresentForEveryScenario() {
        assertThat(BenchmarkScenarioCatalog.metadataByKey())
            .hasSize(BenchmarkScenarioCatalog.expectedCount());
        BenchmarkScenarioCatalog.scenarioKeys().forEach(key -> {
            BenchmarkScenarioCatalog.ScenarioMetadata metadata = BenchmarkScenarioCatalog.metadata(key);
            assertThat(metadata.name()).isNotBlank();
            assertThat(metadata.category()).isNotBlank();
            assertThat(metadata.workload()).isNotBlank();
            assertThat(metadata.enhancedQueriesPath()).isNotBlank();
            assertThat(metadata.streamProjectionsPath()).isNotBlank();
            assertThat(metadata.expectedResult()).isNotBlank();
        });
    }
}
