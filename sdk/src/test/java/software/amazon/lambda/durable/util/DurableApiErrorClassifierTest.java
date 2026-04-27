// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;

class DurableApiErrorClassifierTest {

    @ParameterizedTest
    @MethodSource("kmsExceptions")
    void classifyException_kmsError_returnsNonRetryable(String errorCode, String message) {
        var error = awsError(502, errorCode, message);
        var result = DurableApiErrorClassifier.classifyException(error);
        assertInstanceOf(UnrecoverableDurableExecutionException.class, result);
        assertFalse(result.isRetryable());
        assertEquals(errorCode, result.getErrorObject().errorType());
        assertTrue(result.getErrorObject().errorMessage().contains(message));
    }

    static Stream<Arguments> kmsExceptions() {
        return Stream.of(
                Arguments.of(
                        "KMSAccessDeniedException",
                        "Lambda was unable to decrypt the environment variables because KMS access was denied."
                                + " Please check the function's KMS key settings."
                                + " KMS Exception: AccessDeniedException"),
                Arguments.of(
                        "KMSDisabledException",
                        "Lambda was unable to decrypt the environment variables because the KMS key used is disabled."
                                + " Please check the function's KMS key settings."),
                Arguments.of(
                        "KMSInvalidStateException",
                        "Lambda was unable to decrypt the environment variables because the KMS key is in an invalid"
                                + " state for Decrypt. Please check the function's KMS key settings."),
                Arguments.of(
                        "KMSNotFoundException",
                        "Lambda was unable to decrypt the environment variables because the KMS key was not found."
                                + " Please check the function's KMS key settings."));
    }

    @Test
    void classifyException_clientError4xx_returnsNonRetryable() {
        var error = awsError(403, "AccessDeniedException", "User is not authorized");

        var result = DurableApiErrorClassifier.classifyException(error);
        assertInstanceOf(UnrecoverableDurableExecutionException.class, result);
        assertFalse(result.isRetryable());
    }

    @Test
    void classifyException_invalidCheckpointToken_returnsRetryable() {
        var error = awsError(400, "InvalidParameterValueException", "Invalid Checkpoint Token: token expired");

        var result = DurableApiErrorClassifier.classifyException(error);
        assertInstanceOf(UnrecoverableDurableExecutionException.class, result);
        assertTrue(result.isRetryable());
    }

    @Test
    void classifyException_invalidParameterValueNonToken_returnsNonRetryable() {
        var error = awsError(
                400,
                "InvalidParameterValueException",
                "The runtime parameter of python3.8 is no longer" + " supported for creating or updating functions.");

        var result = DurableApiErrorClassifier.classifyException(error);
        assertInstanceOf(UnrecoverableDurableExecutionException.class, result);
        assertFalse(result.isRetryable());
    }

    @Test
    void classifyException_throttled429_returnsRetryable() {
        var error = awsError(429, "TooManyRequestsException", "Rate exceeded");

        var result = DurableApiErrorClassifier.classifyException(error);
        assertInstanceOf(UnrecoverableDurableExecutionException.class, result);
        assertTrue(result.isRetryable());
    }

    @Test
    void classifyException_serverError500_returnsRetryable() {
        var error = awsError(500, "ServiceException", "Service encountered an error");

        var result = DurableApiErrorClassifier.classifyException(error);
        assertInstanceOf(UnrecoverableDurableExecutionException.class, result);
        assertTrue(result.isRetryable());
    }

    @Test
    void classifyException_nonMatchingErrorCode502_returnsRetryable() {
        var error = awsError(502, "ServiceException", "Service unavailable");

        var result = DurableApiErrorClassifier.classifyException(error);
        assertInstanceOf(UnrecoverableDurableExecutionException.class, result);
        assertTrue(result.isRetryable());
    }

    @Test
    void classifyException_nonRetryableError_preservesErrorDetails() {
        var error = awsError(
                502,
                "KMSAccessDeniedException",
                "Lambda was unable to decrypt the environment variables because KMS access was denied."
                        + " Please check the function's KMS key settings."
                        + " KMS Exception: AccessDeniedException");

        var result = DurableApiErrorClassifier.classifyException(error);
        assertInstanceOf(UnrecoverableDurableExecutionException.class, result);
        assertFalse(result.isRetryable());
        assertEquals("KMSAccessDeniedException", result.getErrorObject().errorType());
        assertTrue(result.getErrorObject()
                .errorMessage()
                .contains("Lambda was unable to decrypt the environment variables"));
    }

    private static AwsServiceException awsError(int statusCode, String errorCode, String message) {
        return AwsServiceException.builder()
                .message(message)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode(errorCode)
                        .errorMessage(message)
                        .sdkHttpResponse(
                                SdkHttpResponse.builder().statusCode(statusCode).build())
                        .build())
                .statusCode(statusCode)
                .build();
    }
}
