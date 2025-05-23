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

package software.amazon.awssdk.core.exception;

import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.core.internal.retry.SdkDefaultRetrySetting;

/**
 * Base type for all client exceptions thrown by the SDK.
 *
 * This exception is thrown when service could not be contacted for a response,
 * or when client is unable to parse the response from service.
 * <p>
 * Exceptions that extend {@link SdkClientException} are assumed to be not retryable, with a few exceptions:
 * <ul>
 *     <li>{@link RetryableException} - usable when calls should explicitly be retried</li>
 *     <li>Exceptions mentioned as a retryable exception in {@link SdkDefaultRetrySetting}</li>
 * </ul>
 *
 * @see SdkServiceException
 */
@SdkPublicApi
public class SdkClientException extends SdkException {

    protected SdkClientException(Builder b) {
        super(b);
    }

    public static SdkClientException create(String message) {
        return SdkClientException.builder().message(message).build();
    }

    public static SdkClientException create(String message, Throwable cause) {
        return SdkClientException.builder().message(message).cause(cause).build();
    }

    @Override
    public String getMessage() {
        String message = rawMessage();
        if (numAttempts() != null) {
            SdkDiagnostics sdkDiagnostics = SdkDiagnostics.builder().numAttempts(numAttempts()).build();
            message = message + " " + sdkDiagnostics;
        }
        return message;
    }

    /**
     * Create a {@link Builder} initialized with the properties of this {@code SdkClientException}.
     *
     * @return A new builder initialized with this config's properties.
     */
    @Override
    public Builder toBuilder() {
        return new BuilderImpl(this);
    }

    /**
     * @return {@link Builder} instance to construct a new {@link SdkClientException}.
     */
    public static Builder builder() {
        return new BuilderImpl();
    }

    public interface Builder extends SdkException.Builder {

        @Override
        Builder message(String message);

        @Override
        Builder cause(Throwable cause);

        @Override
        Builder writableStackTrace(Boolean writableStackTrace);

        @Override
        SdkClientException build();

        @Override
        Builder numAttempts(Integer numAttempts);
    }

    protected static class BuilderImpl extends SdkException.BuilderImpl implements Builder {

        protected BuilderImpl() {
        }

        protected BuilderImpl(SdkClientException ex) {
            super(ex);
        }

        @Override
        public Builder message(String message) {
            this.message = message;
            return this;
        }

        @Override
        public Builder cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        @Override
        public Builder writableStackTrace(Boolean writableStackTrace) {
            this.writableStackTrace = writableStackTrace;
            return this;
        }

        @Override
        public Builder numAttempts(Integer numAttempts) {
            this.numAttempts = numAttempts;
            return this;
        }

        @Override
        public SdkClientException build() {
            return new SdkClientException(this);
        }
    }
}