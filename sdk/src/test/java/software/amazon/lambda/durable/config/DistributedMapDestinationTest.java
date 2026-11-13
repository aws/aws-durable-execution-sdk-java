// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class DistributedMapDestinationTest {

    @Test
    void successes_defaultsIncludeOutputOnly() {
        var destination = DistributedMapDestination.s3Successes("s3://bucket/results");

        assertEquals("bucket", destination.bucket());
        assertEquals("results", destination.prefix());
        assertFalse(destination.includeInput());
        assertTrue(destination.includeOutput());
        assertNull(destination.expectedBucketOwner());
    }

    @Test
    void successes_withoutPath_prefixIsEmpty() {
        var destination = DistributedMapDestination.s3Successes("s3://bucket");

        assertEquals("bucket", destination.bucket());
        assertEquals("", destination.prefix());
    }

    @Test
    void successes_withAllArguments() {
        var destination = DistributedMapDestination.s3Successes("s3://bucket/out", true, true, "123456789012");

        assertEquals("bucket", destination.bucket());
        assertEquals("out", destination.prefix());
        assertTrue(destination.includeInput());
        assertTrue(destination.includeOutput());
        assertEquals("123456789012", destination.expectedBucketOwner());
    }

    @Test
    void successes_allIncludeOff_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapDestination.s3Successes("s3://bucket/out", false, false, null));
        assertEquals("success destination must include at least one of input or output", exception.getMessage());
    }

    @Test
    void successDestination_allIncludeOff_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DistributedMapDestination.Success("bucket", "", false, false, null));
        assertEquals("success destination must include at least one of input or output", exception.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void successDestination_nullOrEmptyBucket_shouldThrow(String bucket) {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DistributedMapDestination.Success(bucket, "", false, true, null));
        assertEquals("success destination bucket cannot be empty", exception.getMessage());
    }

    @Test
    void failures_defaultsIncludeInputAndError() {
        var destination = DistributedMapDestination.s3Failures("s3://bucket/errors");

        assertEquals("bucket", destination.bucket());
        assertEquals("errors", destination.prefix());
        assertTrue(destination.includeInput());
        assertTrue(destination.includeError());
        assertNull(destination.expectedBucketOwner());
    }

    @Test
    void failures_withAllArguments() {
        var destination = DistributedMapDestination.s3Failures("s3://bucket/err", true, false, "123456789012");

        assertEquals("bucket", destination.bucket());
        assertEquals("err", destination.prefix());
        assertTrue(destination.includeInput());
        assertFalse(destination.includeError());
        assertEquals("123456789012", destination.expectedBucketOwner());
    }

    @Test
    void failures_allIncludeOff_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapDestination.s3Failures("s3://bucket/err", false, false, null));
        assertEquals("failure destination must include at least one of input or error", exception.getMessage());
    }

    @Test
    void failureDestination_allIncludeOff_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DistributedMapDestination.Failure("bucket", "", false, false, null));
        assertEquals("failure destination must include at least one of input or error", exception.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void failureDestination_nullOrEmptyBucket_shouldThrow(String bucket) {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DistributedMapDestination.Failure(bucket, "", true, true, null));
        assertEquals("failure destination bucket cannot be empty", exception.getMessage());
    }

    @Test
    void successes_invalidBucketOwner_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapDestination.s3Successes("s3://bucket/out", false, true, "123"));
        assertEquals("expectedBucketOwner must be a 12-digit account id, got: 123", exception.getMessage());
    }

    @Test
    void successes_invalidUri_shouldThrow() {
        var exception =
                assertThrows(IllegalArgumentException.class, () -> DistributedMapDestination.s3Successes("bucket/out"));
        assertEquals("S3 URI must start with s3://, got: bucket/out", exception.getMessage());
    }

    @Test
    void destinationConfig_onSuccess_routesSuccessOnly() {
        var success = DistributedMapDestination.s3Successes("s3://bucket/ok");
        var config = DistributedMapDestination.of(success);

        assertSame(success, config.onSuccess());
        assertNull(config.onFailure());
    }

    @Test
    void destinationConfig_onFailure_routesFailureOnly() {
        var failure = DistributedMapDestination.s3Failures("s3://bucket/bad");
        var config = DistributedMapDestination.of(failure);

        assertNull(config.onSuccess());
        assertSame(failure, config.onFailure());
    }

    @Test
    void destinationConfig_of_routesBoth() {
        var success = DistributedMapDestination.s3Successes("s3://bucket/ok");
        var failure = DistributedMapDestination.s3Failures("s3://bucket/bad");
        var config = DistributedMapDestination.of(success, failure);

        assertSame(success, config.onSuccess());
        assertSame(failure, config.onFailure());
    }

    // ------------------------------------------------------------------
    // key prefix length
    // ------------------------------------------------------------------

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    @Test
    void successes_prefixAtTheByteLimit_isAccepted() {
        var prefix = repeat("a", 900);

        assertEquals(
                prefix,
                DistributedMapDestination.s3Successes("s3://bucket/" + prefix).prefix());
    }

    @Test
    void successes_prefixOverTheByteLimit_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapDestination.s3Successes("s3://bucket/" + repeat("a", 901)));
        assertEquals("destination key prefix must be at most 900 UTF-8 bytes, got: 901", exception.getMessage());
    }

    @Test
    void failures_prefixOverTheByteLimit_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapDestination.s3Failures("s3://bucket/" + repeat("a", 901)));
        assertEquals("destination key prefix must be at most 900 UTF-8 bytes, got: 901", exception.getMessage());
    }

    @Test
    void successes_prefixCountsUtf8BytesNotCharacters() {
        // This character is two UTF-8 bytes, so 450 of them are exactly 900 bytes while being only 450 characters.
        var atTheLimit = repeat("\u00e9", 450);
        assertEquals(450, atTheLimit.length());
        assertEquals(
                atTheLimit,
                DistributedMapDestination.s3Successes("s3://bucket/" + atTheLimit)
                        .prefix());

        var overTheLimit = atTheLimit + "\u00e9";
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapDestination.s3Successes("s3://bucket/" + overTheLimit));
        assertEquals("destination key prefix must be at most 900 UTF-8 bytes, got: 902", exception.getMessage());
    }

    @Test
    void successes_prefixLengthCountsTheTrailingSlash() {
        // 900 characters plus the trailing slash is 901 bytes, so the slash is what pushes it over.
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapDestination.s3Successes("s3://bucket/" + repeat("a", 900) + "/"));
        assertEquals("destination key prefix must be at most 900 UTF-8 bytes, got: 901", exception.getMessage());
    }
}
