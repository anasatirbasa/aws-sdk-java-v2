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
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.utils.Validate;

/**
 * One projected summary row: group key + visible aggregate values.
 */
@SdkPublicApi
public final class SummaryRow {

    private final Map<String, Object> key;
    private final Map<String, Number> aggregates;
    private final Map<String, Object> attributes;

    public SummaryRow(Map<String, Object> key, Map<String, Number> aggregates) {
        this(key, aggregates, Collections.emptyMap());
    }

    public SummaryRow(Map<String, Object> key,
                      Map<String, Number> aggregates,
                      Map<String, Object> attributes) {
        this.key = Collections.unmodifiableMap(new LinkedHashMap<>(
            Validate.paramNotNull(key, "key")));
        this.aggregates = Collections.unmodifiableMap(new LinkedHashMap<>(
            Validate.paramNotNull(aggregates, "aggregates")));
        this.attributes = attributes == null || attributes.isEmpty()
                          ? Collections.emptyMap()
                          : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public Map<String, Object> key() {
        return key;
    }

    public Map<String, Number> aggregates() {
        return aggregates;
    }

    /** Denormalized non-key attributes copied from source items at write time. */
    public Map<String, Object> attributes() {
        return attributes;
    }

    /**
     * Attribute map used for HAVING predicates (aggregates + key attrs + carry-forward attrs).
     */
    public Map<String, Object> asHavingItem() {
        Map<String, Object> item = new LinkedHashMap<>(key);
        item.putAll(attributes);
        item.putAll(aggregates);
        return item;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SummaryRow)) {
            return false;
        }
        SummaryRow that = (SummaryRow) o;
        return Objects.equals(key, that.key)
               && Objects.equals(aggregates, that.aggregates)
               && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(key);
        result = 31 * result + Objects.hashCode(aggregates);
        result = 31 * result + Objects.hashCode(attributes);
        return result;
    }
}
