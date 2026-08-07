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
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.utils.Validate;

/**
 * Result of planning or applying a join-projection mutation. Either {@link Skipped} or a
 * list of {@link Write} operations (put / delete / update).
 */
@SdkPublicApi
public abstract class JoinApplyOutcome {

    private JoinApplyOutcome() {
    }

    public abstract Kind kind();

    public enum Kind {
        WRITES,
        SKIPPED
    }

    public static Skipped skipped(ApplyOutcome.SkipReason reason) {
        return new Skipped(reason);
    }

    public static Writes writes(List<Write> writes) {
        return new Writes(writes);
    }

    public static final class Skipped extends JoinApplyOutcome {
        private final ApplyOutcome.SkipReason reason;

        private Skipped(ApplyOutcome.SkipReason reason) {
            this.reason = Validate.paramNotNull(reason, "reason");
        }

        @Override
        public Kind kind() {
            return Kind.SKIPPED;
        }

        public ApplyOutcome.SkipReason reason() {
            return reason;
        }
    }

    public static final class Writes extends JoinApplyOutcome {
        private final List<Write> writes;

        private Writes(List<Write> writes) {
            this.writes = Collections.unmodifiableList(new ArrayList<>(
                Validate.paramNotNull(writes, "writes")));
        }

        @Override
        public Kind kind() {
            return Kind.WRITES;
        }

        public List<Write> writes() {
            return writes;
        }
    }

    /**
     * A single DynamoDB write against the join target table.
     */
    @SdkPublicApi
    public abstract static class Write {
        private Write() {
        }

        public abstract Op op();

        public enum Op {
            PUT,
            DELETE,
            UPDATE
        }

        public static Put put(PutItemRequest request) {
            return new Put(request);
        }

        public static Delete delete(DeleteItemRequest request) {
            return new Delete(request);
        }

        public static Update update(UpdateItemRequest request) {
            return new Update(request);
        }

        public static final class Put extends Write {
            private final PutItemRequest request;

            private Put(PutItemRequest request) {
                this.request = Validate.paramNotNull(request, "request");
            }

            @Override
            public Op op() {
                return Op.PUT;
            }

            public PutItemRequest request() {
                return request;
            }
        }

        public static final class Delete extends Write {
            private final DeleteItemRequest request;

            private Delete(DeleteItemRequest request) {
                this.request = Validate.paramNotNull(request, "request");
            }

            @Override
            public Op op() {
                return Op.DELETE;
            }

            public DeleteItemRequest request() {
                return request;
            }
        }

        public static final class Update extends Write {
            private final UpdateItemRequest request;

            private Update(UpdateItemRequest request) {
                this.request = Validate.paramNotNull(request, "request");
            }

            @Override
            public Op op() {
                return Op.UPDATE;
            }

            public UpdateItemRequest request() {
                return request;
            }
        }
    }
}
