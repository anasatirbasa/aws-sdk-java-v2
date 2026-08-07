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

package software.amazon.awssdk.enhanced.dynamodb.projection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class CachingParentResolverTest {

    @Test
    public void cachesParentLookups() {
        final int[] calls = {0};
        ParentResolver delegate = key -> {
            calls[0]++;
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", "n");
            return m;
        };
        CachingParentResolver cache = new CachingParentResolver(delegate);
        assertThat(cache.findParent("c1").get("name")).isEqualTo("n");
        assertThat(cache.findParent("c1").get("name")).isEqualTo("n");
        assertThat(calls[0]).isEqualTo(1);
    }
}
