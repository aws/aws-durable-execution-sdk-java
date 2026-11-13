// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.util;

import java.util.regex.Pattern;

/** Shared validation and S3 URI parsing for distributed map configuration. */
public final class DistributedMapValidation {
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final String S3_SCHEME = "s3://";
    private static final int MAX_FUNCTION_NAME_LENGTH = 170;

    private DistributedMapValidation() {}

    public static void validateFunctionName(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalArgumentException("function name cannot be empty");
        }
        if (functionName.length() > MAX_FUNCTION_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "function name must be at most 170 characters, got: " + functionName.length());
        }
    }

    public static void validateBucketOwner(String expectedBucketOwner) {
        if (expectedBucketOwner != null
                && !ACCOUNT_ID.matcher(expectedBucketOwner).matches()) {
            throw new IllegalArgumentException(
                    "expectedBucketOwner must be a 12-digit account id, got: " + expectedBucketOwner);
        }
    }

    public static ParsedS3Uri parseS3Uri(String uri) {
        if (uri == null || !uri.startsWith(S3_SCHEME)) {
            throw new IllegalArgumentException("S3 URI must start with s3://, got: " + uri);
        }
        var remainder = uri.substring(S3_SCHEME.length());
        var slash = remainder.indexOf('/');
        var bucket = slash < 0 ? remainder : remainder.substring(0, slash);
        var path = slash < 0 ? null : remainder.substring(slash + 1);
        if (bucket.isEmpty()) {
            throw new IllegalArgumentException("S3 URI must contain a bucket, got: " + uri);
        }
        return new ParsedS3Uri(bucket, path == null || path.isEmpty() ? null : path);
    }

    public record ParsedS3Uri(String bucket, String path) {}
}
