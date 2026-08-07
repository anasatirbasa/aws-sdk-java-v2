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
import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.utils.Validate;

/**
 * One page of summary rows plus an optional opaque cursor for the next page.
 */
@SdkPublicApi
public final class SummaryPage {

    private final List<SummaryRow> rows;
    private final String cursor;

    public SummaryPage(List<SummaryRow> rows, String cursor) {
        this.rows = Collections.unmodifiableList(
            Validate.paramNotNull(rows, "rows"));
        this.cursor = cursor;
    }

    public List<SummaryRow> rows() {
        return rows;
    }

    /**
     * Opaque next-page token, or {@code null} when exhausted.
     */
    public String cursor() {
        return cursor;
    }

    public boolean hasMore() {
        return cursor != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SummaryPage)) {
            return false;
        }
        SummaryPage that = (SummaryPage) o;
        return Objects.equals(rows, that.rows) && Objects.equals(cursor, that.cursor);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(rows);
        result = 31 * result + Objects.hashCode(cursor);
        return result;
    }
}
