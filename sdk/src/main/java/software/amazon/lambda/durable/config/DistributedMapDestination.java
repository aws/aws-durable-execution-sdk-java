// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import software.amazon.lambda.durable.util.DistributedMapValidation;

/** Destination routing for distributed map run results. */
public record DistributedMapDestination(Success onSuccess, Failure onFailure) {
    /** Routes only succeeded item records. */
    public static DistributedMapDestination of(Success onSuccess) {
        return new DistributedMapDestination(onSuccess, null);
    }

    /** Routes only failed item records. */
    public static DistributedMapDestination of(Failure onFailure) {
        return new DistributedMapDestination(null, onFailure);
    }

    /** Routes both succeeded and failed item records. */
    public static DistributedMapDestination of(Success onSuccess, Failure onFailure) {
        return new DistributedMapDestination(onSuccess, onFailure);
    }

    /** Route succeeded item records to an S3 prefix (defaults to include output only). */
    public static Success s3Successes(String prefixUri) {
        return s3Successes(prefixUri, false, true, null);
    }

    /** Route succeeded item records to an S3 prefix. */
    public static Success s3Successes(
            String prefixUri, boolean includeInput, boolean includeOutput, String expectedBucketOwner) {
        var parsed = DistributedMapValidation.parseS3Uri(prefixUri);
        return new Success(
                parsed.bucket(),
                parsed.path() != null ? parsed.path() : "",
                includeInput,
                includeOutput,
                expectedBucketOwner);
    }

    /** Route permanently-failed item records to an S3 prefix (defaults to include input and error). */
    public static Failure s3Failures(String prefixUri) {
        return s3Failures(prefixUri, true, true, null);
    }

    /** Route permanently-failed item records to an S3 prefix. */
    public static Failure s3Failures(
            String prefixUri, boolean includeInput, boolean includeError, String expectedBucketOwner) {
        var parsed = DistributedMapValidation.parseS3Uri(prefixUri);
        return new Failure(
                parsed.bucket(),
                parsed.path() != null ? parsed.path() : "",
                includeInput,
                includeError,
                expectedBucketOwner);
    }

    /** S3 destination for succeeded distributed map items. */
    public record Success(
            String bucket, String prefix, boolean includeInput, boolean includeOutput, String expectedBucketOwner) {

        public Success {
            if (bucket == null || bucket.isEmpty()) {
                throw new IllegalArgumentException("success destination bucket cannot be empty");
            }
            DistributedMapValidation.validateBucketOwner(expectedBucketOwner);
            if (!includeInput && !includeOutput) {
                throw new IllegalArgumentException("success destination must include at least one of input or output");
            }
        }
    }

    /** S3 destination for permanently-failed distributed map items. */
    public record Failure(
            String bucket, String prefix, boolean includeInput, boolean includeError, String expectedBucketOwner) {

        public Failure {
            if (bucket == null || bucket.isEmpty()) {
                throw new IllegalArgumentException("failure destination bucket cannot be empty");
            }
            DistributedMapValidation.validateBucketOwner(expectedBucketOwner);
            if (!includeInput && !includeError) {
                throw new IllegalArgumentException("failure destination must include at least one of input or error");
            }
        }
    }
}
