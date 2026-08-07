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
 * Target summary table for a projection (partition key, optional sort key).
 */
@SdkPublicApi
public final class TargetTable {

    private final String tableName;
    private final String partitionKey;
    private final String sortKey;

    private TargetTable(String tableName, String partitionKey, String sortKey) {
        this.tableName = Validate.paramNotBlank(tableName, "tableName");
        this.partitionKey = Validate.paramNotBlank(partitionKey, "partitionKey");
        this.sortKey = sortKey;
    }

    public static TargetTable of(String tableName, String partitionKey) {
        return new TargetTable(tableName, partitionKey, null);
    }

    public static TargetTable of(String tableName, String partitionKey, String sortKey) {
        return new TargetTable(tableName, partitionKey, sortKey);
    }

    public String tableName() {
        return tableName;
    }

    public String partitionKey() {
        return partitionKey;
    }

    public String sortKey() {
        return sortKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TargetTable)) {
            return false;
        }
        TargetTable that = (TargetTable) o;
        return Objects.equals(tableName, that.tableName)
               && Objects.equals(partitionKey, that.partitionKey)
               && Objects.equals(sortKey, that.sortKey);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(tableName);
        result = 31 * result + Objects.hashCode(partitionKey);
        result = 31 * result + Objects.hashCode(sortKey);
        return result;
    }
}
