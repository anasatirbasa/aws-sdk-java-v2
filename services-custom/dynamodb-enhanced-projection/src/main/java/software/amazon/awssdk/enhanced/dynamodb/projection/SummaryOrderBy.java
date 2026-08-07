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
 * Order-by clause for projected summary rows (by aggregate alias or group-key attribute).
 */
@SdkPublicApi
public final class SummaryOrderBy {

    private final String name;
    private final SortDirection direction;
    private final boolean byAggregate;

    private SummaryOrderBy(String name, SortDirection direction, boolean byAggregate) {
        this.name = Validate.paramNotBlank(name, "name");
        this.direction = Validate.paramNotNull(direction, "direction");
        this.byAggregate = byAggregate;
    }

    public static SummaryOrderBy byAggregate(String aggregateAlias, SortDirection direction) {
        return new SummaryOrderBy(aggregateAlias, direction, true);
    }

    public static SummaryOrderBy byKey(String keyAttribute, SortDirection direction) {
        return new SummaryOrderBy(keyAttribute, direction, false);
    }

    public String name() {
        return name;
    }

    public SortDirection direction() {
        return direction;
    }

    public boolean byAggregate() {
        return byAggregate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SummaryOrderBy)) {
            return false;
        }
        SummaryOrderBy that = (SummaryOrderBy) o;
        return byAggregate == that.byAggregate
               && Objects.equals(name, that.name)
               && direction == that.direction;
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(name);
        result = 31 * result + Objects.hashCode(direction);
        result = 31 * result + Objects.hashCode(byAggregate);
        return result;
    }
}
