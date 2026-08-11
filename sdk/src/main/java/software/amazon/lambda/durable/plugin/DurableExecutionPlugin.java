// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

/**
 * Plugin interface for instrumenting durable execution lifecycle events.
 *
 * <p>Implement this interface to integrate observability tools (OpenTelemetry, Datadog, etc.) with the durable
 * execution SDK. The SDK calls these hooks at key lifecycle points without requiring modifications to core SDK code.
 *
 * <p>All methods have default no-op implementations, allowing plugins to override only the hooks they need.
 *
 * <p>Plugin errors are isolated — exceptions thrown by plugin methods are caught and logged but never disrupt SDK
 * execution.
 */
public interface DurableExecutionPlugin {

    // ─── Invocation-level hooks ──────────────────────────────────────────

    /**
     * Called at the start of each Lambda invocation. Use to set up per-invocation state (trace ID, invocation span).
     *
     * <p>Check {@link InvocationInfo#isFirstInvocation()} to detect the first invocation of an execution (useful for
     * sampling decisions or execution-level span creation).
     */
    default void onInvocationStart(InvocationInfo info) {}

    /**
     * Called at the end of each Lambda invocation. Use to flush spans/metrics before Lambda freezes.
     *
     * <p>This hook is awaited — the SDK blocks until it returns. This is the only safe flush point before Lambda
     * freezes the execution environment.
     *
     * <p>Check {@link InvocationEndInfo#invocationStatus()} to detect if the execution reached a terminal state in this
     * invocation (useful for writing summary records or flushing final data).
     */
    default void onInvocationEnd(InvocationEndInfo info) {}

    // ─── Operation-level hooks ───────────────────────────────────────────

    /** Called when an operation starts (including replay). Use for logging/metrics that want replay visibility. */
    default void onOperationStart(OperationInfo info) {}

    /**
     * Called once when an operation's terminal status is first observed.
     *
     * <p>This is usually the invocation that completes the operation, but it can also be a later invocation that first
     * observes a completion which happened while the execution was suspended (for example an operation that finished
     * during a wait). In that case {@link OperationEndInfo#isReplay()} is {@code true}, so do not assume this hook only
     * fires with {@code isReplay() == false}. It does not fire again on subsequent replays of an already-observed
     * completion.
     *
     * <p>The OTel plugin creates operation spans here with backfilled start/end timestamps (emitting a linked
     * continuation span when the completion is observed on a replaying invocation).
     */
    default void onOperationEnd(OperationEndInfo info) {}

    /**
     * Called when a checkpoint response changes the status of one or more operations.
     *
     * <p>Receives the operations that changed and a snapshot of all operations.
     */
    default void onOperationChange(OperationChangeInfo info) {}

    // ─── User function hooks ─────────────────────────────────────────────

    /**
     * Called when a user-provided function starts executing. This fires for both step attempts (with {@code attempt}
     * set) and child context functions (with {@code attempt} null).
     *
     * <p>This hook fires on the same thread as user code, so plugins can set OTel context via
     * {@code Context.makeCurrent()} here.
     */
    default void onUserFunctionStart(UserFunctionStartInfo info) {}

    /**
     * Called when a user-provided function finishes executing. This fires for both step attempts and child context
     * functions.
     *
     * <p>This hook fires on the same thread as user code, so plugins can close OTel scopes here.
     *
     * <p>It fires for every terminal outcome of the user function: normal return, a thrown failure, and suspension.
     * {@link UserFunctionEndInfo#succeeded()} is {@code true} only on normal return; it is {@code false} for both a
     * genuine failure and a suspension. On suspension the {@link UserFunctionEndInfo#error()} is the SDK's internal
     * {@code SuspendExecutionException}, which is not a user error — plugins that count failures should treat a
     * suspension as a neutral outcome (for example by checking the error type) rather than a failure.
     */
    default void onUserFunctionEnd(UserFunctionEndInfo info) {}
}
