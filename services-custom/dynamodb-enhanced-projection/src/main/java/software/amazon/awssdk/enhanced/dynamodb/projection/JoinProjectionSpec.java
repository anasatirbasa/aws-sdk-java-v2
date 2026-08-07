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
import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.utils.Validate;

/**
 * Declaration of a join projection that materializes target rows from left (parent) and right
 * (child) stream events, denormalizing selected attributes according to {@link JoinType}.
 *
 * <p>Target table shape: partition key = join key (e.g. {@code customerId}), sort key =
 * right unique key (e.g. {@code orderId}). For {@link JoinType#LEFT} / {@link JoinType#FULL},
 * parent-only rows use sort key {@link JoinProjectionApplyEngine#LEFT_ONLY_SORT_KEY}.
 */
@SdkPublicApi
public final class JoinProjectionSpec {

    private final String name;
    private final JoinType joinType;
    private final String leftEntityType;
    private final String rightEntityType;
    private final String leftJoinAttribute;
    private final String rightJoinAttribute;
    private final String rightSortKeyAttribute;
    private final List<String> leftFields;
    private final List<String> rightFields;
    private final TargetTable target;

    private JoinProjectionSpec(Builder builder) {
        this.name = Validate.paramNotBlank(builder.name, "name");
        this.joinType = builder.joinType == null ? JoinType.INNER : builder.joinType;
        this.leftEntityType = Validate.paramNotBlank(builder.leftEntityType, "leftEntityType");
        this.rightEntityType = Validate.paramNotBlank(builder.rightEntityType, "rightEntityType");
        this.leftJoinAttribute = Validate.paramNotBlank(builder.leftJoinAttribute, "leftJoinAttribute");
        this.rightJoinAttribute = Validate.paramNotBlank(builder.rightJoinAttribute, "rightJoinAttribute");
        this.rightSortKeyAttribute = Validate.paramNotBlank(builder.rightSortKeyAttribute,
                                                            "rightSortKeyAttribute");
        this.leftFields = Collections.unmodifiableList(new ArrayList<>(
            Validate.paramNotNull(builder.leftFields, "leftFields")));
        this.rightFields = Collections.unmodifiableList(new ArrayList<>(
            Validate.paramNotNull(builder.rightFields, "rightFields")));
        this.target = Validate.paramNotNull(builder.target, "target");
        if (target.sortKey() == null) {
            throw new IllegalArgumentException("join projection target requires a sort key");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String name() {
        return name;
    }

    public JoinType joinType() {
        return joinType;
    }

    public String leftEntityType() {
        return leftEntityType;
    }

    public String rightEntityType() {
        return rightEntityType;
    }

    public String leftJoinAttribute() {
        return leftJoinAttribute;
    }

    public String rightJoinAttribute() {
        return rightJoinAttribute;
    }

    /**
     * Attribute on the right entity used as the join-table sort key (typically the child's
     * unique id, e.g. {@code orderId}).
     */
    public String rightSortKeyAttribute() {
        return rightSortKeyAttribute;
    }

    public List<String> leftFields() {
        return leftFields;
    }

    public List<String> rightFields() {
        return rightFields;
    }

    public TargetTable target() {
        return target;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JoinProjectionSpec)) {
            return false;
        }
        JoinProjectionSpec that = (JoinProjectionSpec) o;
        return joinType == that.joinType
               && Objects.equals(name, that.name)
               && Objects.equals(leftEntityType, that.leftEntityType)
               && Objects.equals(rightEntityType, that.rightEntityType)
               && Objects.equals(leftJoinAttribute, that.leftJoinAttribute)
               && Objects.equals(rightJoinAttribute, that.rightJoinAttribute)
               && Objects.equals(rightSortKeyAttribute, that.rightSortKeyAttribute)
               && Objects.equals(leftFields, that.leftFields)
               && Objects.equals(rightFields, that.rightFields)
               && Objects.equals(target, that.target);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(name);
        result = 31 * result + Objects.hashCode(joinType);
        result = 31 * result + Objects.hashCode(leftEntityType);
        result = 31 * result + Objects.hashCode(rightEntityType);
        result = 31 * result + Objects.hashCode(leftJoinAttribute);
        result = 31 * result + Objects.hashCode(rightJoinAttribute);
        result = 31 * result + Objects.hashCode(rightSortKeyAttribute);
        result = 31 * result + Objects.hashCode(leftFields);
        result = 31 * result + Objects.hashCode(rightFields);
        result = 31 * result + Objects.hashCode(target);
        return result;
    }

    public static final class Builder {
        private String name;
        private JoinType joinType = JoinType.INNER;
        private String leftEntityType;
        private String rightEntityType;
        private String leftJoinAttribute;
        private String rightJoinAttribute;
        private String rightSortKeyAttribute;
        private List<String> leftFields = new ArrayList<>();
        private List<String> rightFields = new ArrayList<>();
        private TargetTable target;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder joinType(JoinType joinType) {
            this.joinType = joinType;
            return this;
        }

        public Builder leftEntityType(String leftEntityType) {
            this.leftEntityType = leftEntityType;
            return this;
        }

        public Builder rightEntityType(String rightEntityType) {
            this.rightEntityType = rightEntityType;
            return this;
        }

        public Builder leftJoinAttribute(String leftJoinAttribute) {
            this.leftJoinAttribute = leftJoinAttribute;
            return this;
        }

        public Builder rightJoinAttribute(String rightJoinAttribute) {
            this.rightJoinAttribute = rightJoinAttribute;
            return this;
        }

        public Builder rightSortKeyAttribute(String rightSortKeyAttribute) {
            this.rightSortKeyAttribute = rightSortKeyAttribute;
            return this;
        }

        public Builder leftFields(String... fields) {
            this.leftFields = new ArrayList<>();
            if (fields != null) {
                Collections.addAll(this.leftFields, fields);
            }
            return this;
        }

        public Builder rightFields(String... fields) {
            this.rightFields = new ArrayList<>();
            if (fields != null) {
                Collections.addAll(this.rightFields, fields);
            }
            return this;
        }

        public Builder target(TargetTable target) {
            this.target = target;
            return this;
        }

        public JoinProjectionSpec build() {
            return new JoinProjectionSpec(this);
        }
    }
}
