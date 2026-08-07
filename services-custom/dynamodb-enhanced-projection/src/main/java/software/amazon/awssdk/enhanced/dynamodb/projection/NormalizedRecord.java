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
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.utils.Validate;

/**
 * Decoded DynamoDB Streams record used by the apply path and the in-memory harness.
 */
@SdkPublicApi
public final class NormalizedRecord {

    public enum EventName {
        INSERT,
        MODIFY,
        REMOVE
    }

    private final String entityType;
    private final EventName eventName;
    private final Map<String, Object> prev;
    private final Map<String, Object> next;
    private final String sourceItemKey;
    private final String sourceVersion;
    private final String eventId;

    private NormalizedRecord(Builder builder) {
        this.entityType = Validate.paramNotBlank(builder.entityType, "entityType");
        this.eventName = Validate.paramNotNull(builder.eventName, "eventName");
        this.prev = builder.prev == null ? null : Collections.unmodifiableMap(builder.prev);
        this.next = builder.next == null ? null : Collections.unmodifiableMap(builder.next);
        this.sourceItemKey = Validate.paramNotBlank(builder.sourceItemKey, "sourceItemKey");
        this.sourceVersion = Validate.paramNotBlank(builder.sourceVersion, "sourceVersion");
        this.eventId = builder.eventId;
        if (prev == null && next == null) {
            throw new IllegalArgumentException("normalized record requires prev and/or next");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String entityType() {
        return entityType;
    }

    public EventName eventName() {
        return eventName;
    }

    public Map<String, Object> prev() {
        return prev;
    }

    public Map<String, Object> next() {
        return next;
    }

    public String sourceItemKey() {
        return sourceItemKey;
    }

    public String sourceVersion() {
        return sourceVersion;
    }

    public String eventId() {
        return eventId;
    }

    public Map<String, Object> activeImage() {
        return next != null ? next : prev;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NormalizedRecord)) {
            return false;
        }
        NormalizedRecord that = (NormalizedRecord) o;
        return Objects.equals(entityType, that.entityType)
               && eventName == that.eventName
               && Objects.equals(prev, that.prev)
               && Objects.equals(next, that.next)
               && Objects.equals(sourceItemKey, that.sourceItemKey)
               && Objects.equals(sourceVersion, that.sourceVersion)
               && Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(entityType);
        result = 31 * result + Objects.hashCode(eventName);
        result = 31 * result + Objects.hashCode(prev);
        result = 31 * result + Objects.hashCode(next);
        result = 31 * result + Objects.hashCode(sourceItemKey);
        result = 31 * result + Objects.hashCode(sourceVersion);
        result = 31 * result + Objects.hashCode(eventId);
        return result;
    }

    public static final class Builder {
        private String entityType;
        private EventName eventName;
        private Map<String, Object> prev;
        private Map<String, Object> next;
        private String sourceItemKey;
        private String sourceVersion;
        private String eventId;

        public Builder entityType(String entityType) {
            this.entityType = entityType;
            return this;
        }

        public Builder eventName(EventName eventName) {
            this.eventName = eventName;
            return this;
        }

        public Builder prev(Map<String, Object> prev) {
            this.prev = prev;
            return this;
        }

        public Builder next(Map<String, Object> next) {
            this.next = next;
            return this;
        }

        public Builder sourceItemKey(String sourceItemKey) {
            this.sourceItemKey = sourceItemKey;
            return this;
        }

        public Builder sourceVersion(String sourceVersion) {
            this.sourceVersion = sourceVersion;
            return this;
        }

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public NormalizedRecord build() {
            return new NormalizedRecord(this);
        }
    }
}
