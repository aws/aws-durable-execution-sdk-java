// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import java.util.Set;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.lambda.model.ErrorObject;

/**
 * Classifies AWS service exceptions from Durable Execution API calls as execution-level (non-retryable) or
 * invocation-level (retryable).
 *
 * <p>Execution-level errors throw {@link UnrecoverableDurableExecutionException} to terminate the execution
 * immediately. These represent permanent customer-side issues (e.g., KMS key misconfiguration) that will not
 * self-resolve on retry.
 *
 * <p>Invocation-level errors are allowed to propagate, crashing the current Lambda invocation so the backend can retry
 * with a fresh invocation.
 *
 * <p>To add a new non-retryable error, add its error code to {@link #NON_RETRYABLE_ERROR_CODES}.
 */
public final class DurableApiErrorClassifier {

    /**
     * Error codes that represent non-retryable customer errors. When a Durable Execution API call fails with one of
     * these error codes, the execution is terminated immediately.
     *
     * <p>These error codes are documented under the Lambda Invoke API but also apply to other Lambda APIs such as
     * {@code CheckpointDurableExecution} and {@code GetDurableExecutionState}.
     *
     * @see <a href="https://docs.aws.amazon.com/lambda/latest/api/API_Invoke.html">Lambda Invoke API — Errors
     *     (reference for KMS exception names)</a>
     */
    static final Set<String> NON_RETRYABLE_ERROR_CODES = Set.of(
            "KMSAccessDeniedException", "KMSDisabledException", "KMSInvalidStateException", "KMSNotFoundException");

    /** HTTP 429 (Too Many Requests) indicates throttling — a transient condition that resolves on retry. */
    private static final int THROTTLING_STATUS_CODE = 429;

    /**
     * Error code for invalid checkpoint token errors. These occur when the SDK uses a stale checkpoint token that has
     * been superseded by a newer invocation. Retrying with a fresh invocation resolves this.
     *
     * @see <a
     *     href="https://github.com/aws/aws-durable-execution-sdk-js/blob/main/packages/aws-durable-execution-sdk-js/src/utils/checkpoint/checkpoint-manager.ts">JS
     *     SDK classifyCheckpointError</a>
     */
    private static final String INVALID_CHECKPOINT_TOKEN_ERROR_CODE = "InvalidParameterValueException";

    /**
     * Message prefix that distinguishes invalid checkpoint token errors from other
     * {@code InvalidParameterValueException} errors.
     */
    private static final String INVALID_CHECKPOINT_TOKEN_MESSAGE_PREFIX = "Invalid Checkpoint Token";

    private DurableApiErrorClassifier() {}

    /**
     * Classifies the given exception and returns the appropriate exception to throw.
     *
     * <p>Returns {@link UnrecoverableDurableExecutionException} for non-retryable customer errors, or the original
     * exception for retryable errors.
     *
     * <p>Classification rules:
     *
     * <ul>
     *   <li>Error code in {@link #NON_RETRYABLE_ERROR_CODES} → execution error (non-retryable)
     *   <li>4xx + "Invalid Checkpoint Token" → invocation error (retryable, stale token resolves on retry)
     *   <li>4xx (non-429) → execution error (non-retryable customer error)
     *   <li>429, 5xx, unknown → invocation error (retryable)
     * </ul>
     *
     * @param e the AWS service exception from a Durable Execution API call
     * @return an {@link UnrecoverableDurableExecutionException} if non-retryable, or the original exception if
     *     retryable
     */
    public static RuntimeException classifyException(AwsServiceException e) {
        var errorCode = e.awsErrorDetails().errorCode();

        // Non-retryable customer errors: execution is terminally broken (e.g., KMS key misconfiguration)
        if (NON_RETRYABLE_ERROR_CODES.contains(errorCode)) {
            return buildUnrecoverableDurableExecutionException(e);
        }

        var statusCode = e.awsErrorDetails().sdkHttpResponse().statusCode();
        var message = e.getMessage();

        // 4xx errors (excluding throttling) are non-retryable customer errors
        if (statusCode >= 400 && statusCode < 500 && statusCode != THROTTLING_STATUS_CODE) {
            // Stale checkpoint token: occurs when a newer invocation has superseded this one.
            // Retrying with a fresh invocation resolves this, so treat as retryable.
            if (INVALID_CHECKPOINT_TOKEN_ERROR_CODE.equals(errorCode)
                    && message != null
                    && message.startsWith(INVALID_CHECKPOINT_TOKEN_MESSAGE_PREFIX)) {
                return e;
            }
            return buildUnrecoverableDurableExecutionException(e);
        }

        // 429 (throttling), 5xx (service errors), unknown — transient, retryable
        return e;
    }

    private static UnrecoverableDurableExecutionException buildUnrecoverableDurableExecutionException(
            AwsServiceException e) {
        return new UnrecoverableDurableExecutionException(ErrorObject.builder()
                .errorType(e.awsErrorDetails().errorCode())
                .errorMessage(e.getMessage())
                .build());
    }
}
