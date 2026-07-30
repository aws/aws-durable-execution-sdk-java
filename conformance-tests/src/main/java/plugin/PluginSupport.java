// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

/**
 * Shared helpers for the plugin conformance handlers (requirements 10-8..10-18).
 *
 * <p>Every plugin captures the durable execution ARN from the invocation-start hook's info parameter and stamps it as a
 * top-level {@code durableExecutionArn} field on every stdout JSON record, so the runner's execution-scoped CloudWatch
 * filter ({@code $.durableExecutionArn = "<arn>"}) locates the records. These helpers only format that field and
 * classify operation types reported by the real SDK; no behavior is fabricated here.
 */
final class PluginSupport {

    private PluginSupport() {}

    /**
     * Operation type token for step operations as reported by {@code OperationInfo#type()} (AWS SDK
     * {@code OperationType}).
     */
    static boolean isStep(String type) {
        return "STEP".equals(type);
    }

    /** Operation type token for wait operations. */
    static boolean isWait(String type) {
        return "WAIT".equals(type);
    }

    /**
     * Operation type token for step operations as reported by {@code OperationChangeItemInfo#type()}
     * ({@code Operation#typeAsString()} straight off the checkpoint response). Compared case-insensitively because it
     * is a different source string than the {@code OperationType} enum used elsewhere.
     */
    static boolean isStepChange(String type) {
        return type != null && "STEP".equalsIgnoreCase(type);
    }

    /** Parallel-branch sub-type token as reported by {@code OperationSubType#getValue()}. */
    static boolean isBranch(String subType) {
        return "ParallelBranch".equals(subType);
    }

    /** Parent id for a record: the literal string {@code NONE} when the info carries no parent id. */
    static String parentOrNone(String parentId) {
        return parentId == null ? "NONE" : parentId;
    }

    /** Returns {@code , "durableExecutionArn": "<arn>"} when captured, otherwise an empty string. */
    static String arnField(String executionArn) {
        return executionArn == null ? "" : String.format(", \"durableExecutionArn\": \"%s\"", executionArn);
    }
}
