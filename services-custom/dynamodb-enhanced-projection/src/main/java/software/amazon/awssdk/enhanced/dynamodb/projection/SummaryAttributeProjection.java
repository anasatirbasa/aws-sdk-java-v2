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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.annotations.SdkInternalApi;

/**
 * Derives DynamoDB {@code ProjectionExpression} attribute names for summary reads.
 */
@SdkInternalApi
final class SummaryAttributeProjection {

    private SummaryAttributeProjection() {
    }

    static Set<String> forQuery(ProjectionSpec projection, SummaryQuery query) {
        Set<String> attrs = new LinkedHashSet<>();
        TargetTable target = projection.target();
        attrs.add(target.partitionKey());
        if (target.sortKey() != null) {
            attrs.add(target.sortKey());
        }
        attrs.addAll(projection.groupBy());
        attrs.addAll(projection.fields().keySet());
        attrs.addAll(projection.carryForwardAttributes());
        projection.aggregateGsi().ifPresent(gsi -> attrs.add(gsi.partitionKeyAttribute()));
        if (query != null) {
            for (SummaryOrderBy orderBy : query.orderBy()) {
                attrs.add(orderBy.name());
            }
            SummaryConditionAttributes.collect(query.havingCondition(), attrs);
        }
        return attrs;
    }

    static String buildExpression(Set<String> attributeNames) {
        if (attributeNames.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (int ignored = 0; ignored < attributeNames.size(); ignored++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("#a").append(++i);
        }
        return sb.toString();
    }

    static Map<String, String> buildNameMap(Set<String> attributeNames) {
        Map<String, String> names = new LinkedHashMap<>();
        int i = 0;
        for (String attr : attributeNames) {
            names.put("#a" + (++i), attr);
        }
        return names;
    }
}
