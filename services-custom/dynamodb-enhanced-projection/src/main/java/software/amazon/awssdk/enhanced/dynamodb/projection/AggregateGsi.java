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
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.utils.Validate;

/**
 * Declares a GSI on the summary table for ORDER BY an aggregate attribute.
 *
 * <p>Table GSI should use {@code partitionKeyAttribute} as GSI PK and
 * {@code sortKeyAggregateAlias} as GSI SK (the aggregate attribute already stored on the row). On each apply, the projector SETs
 * {@code partitionKeyAttribute = partitionKeyValue}.
 */
@SdkPublicApi
public final class AggregateGsi {

    private final String indexName;
    private final String partitionKeyAttribute;
    private final String partitionKeyValue;
    private final String sortKeyAggregateAlias;

    private AggregateGsi(String indexName,
                         String partitionKeyAttribute,
                         String partitionKeyValue,
                         String sortKeyAggregateAlias) {
        this.indexName = Validate.paramNotBlank(indexName, "indexName");
        this.partitionKeyAttribute = Validate.paramNotBlank(partitionKeyAttribute, "partitionKeyAttribute");
        this.partitionKeyValue = Validate.paramNotBlank(partitionKeyValue, "partitionKeyValue");
        this.sortKeyAggregateAlias = Validate.paramNotBlank(sortKeyAggregateAlias, "sortKeyAggregateAlias");
    }

    /**
     * @param indexName             GSI name
     * @param partitionKeyAttribute attribute written as constant GSI PK (e.g. {@code gsiPk})
     * @param partitionKeyValue     constant value (e.g. {@code ALL})
     * @param sortKeyAggregateAlias aggregate field alias used as GSI SK (e.g. {@code totalAmount})
     */
    public static AggregateGsi of(String indexName,
                                  String partitionKeyAttribute,
                                  String partitionKeyValue,
                                  String sortKeyAggregateAlias) {
        return new AggregateGsi(indexName, partitionKeyAttribute, partitionKeyValue, sortKeyAggregateAlias);
    }

    public String indexName() {
        return indexName;
    }

    public String partitionKeyAttribute() {
        return partitionKeyAttribute;
    }

    public String partitionKeyValue() {
        return partitionKeyValue;
    }

    public String sortKeyAggregateAlias() {
        return sortKeyAggregateAlias;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AggregateGsi)) {
            return false;
        }
        AggregateGsi that = (AggregateGsi) o;
        return Objects.equals(indexName, that.indexName)
               && Objects.equals(partitionKeyAttribute, that.partitionKeyAttribute)
               && Objects.equals(partitionKeyValue, that.partitionKeyValue)
               && Objects.equals(sortKeyAggregateAlias, that.sortKeyAggregateAlias);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(indexName);
        result = 31 * result + Objects.hashCode(partitionKeyAttribute);
        result = 31 * result + Objects.hashCode(partitionKeyValue);
        result = 31 * result + Objects.hashCode(sortKeyAggregateAlias);
        return result;
    }
}
