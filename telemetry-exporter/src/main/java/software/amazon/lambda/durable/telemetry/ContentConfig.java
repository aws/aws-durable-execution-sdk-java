// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Controls what data is included in emitted records (input/output transforms, operation overrides, error inclusion).
 *
 * <p>Mirrors the JS {@code ContentConfig}. {@code input}/{@code output} may be included as-is, excluded, or
 * transformed; all three are honored — the plugin reads execution input from {@code InvocationInfo.executionInput()}
 * and output from {@code InvocationEndInfo.executionResult()}. Per-operation result opt-in is honored via
 * {@link OperationOverride#withResult} (see {@code OperationChangeItemInfo.result()}).
 *
 * <p><b>{@code includeErrors} gates only the per-operation errors</b> ({@code operations[].error} and
 * {@code operationsByName.<name>.error}), as in the JS exporter. With {@code includeErrors(false)} the
 * <em>execution-level</em> {@code error}, including its message, is still emitted on every FAILED record. In Java the
 * execution error is usually the failed operation's own exception rethrown, so its message is usually the operation's
 * message too. Earlier releases of this exporter also suppressed the execution error; that is no longer the case. No
 * setting suppresses the execution error message, and the input/output transforms do not apply to errors. Do not put
 * secrets or personal data in exception messages that can reach the handler's return path: catch such an exception and
 * rethrow one with a safe message, or, for a custom {@link TelemetryExporter}, remove or redact {@code error} in its
 * {@link TelemetryExporter#render render}.
 *
 * <p><b>Transform value shape.</b> The {@code inputTransform} and {@code outputTransform} functions receive a
 * <em>detached, JSON-compatible</em> copy of the value, never the SDK's original Java object: a POJO is presented as a
 * {@code Map}, a list as a {@code List}, and a Java-time type as its JSON representation (for example an
 * {@code Instant} arrives as an ISO-8601 {@code String}). Mutating the argument is therefore safe — it cannot corrupt
 * the cached input snapshot or any later emission — and a non-fatal transform failure omits the field and is logged.
 * VM/thread-termination errors propagate.
 */
@Experimental
public final class ContentConfig {
    private final boolean includeInput;
    private final Function<Object, Object> inputTransform;
    private final boolean includeOutput;
    private final Function<Object, Object> outputTransform;
    private final boolean includeErrors;
    private final List<OperationOverride> overrides;

    private ContentConfig(Builder b) {
        this.includeInput = b.includeInput;
        this.inputTransform = b.inputTransform;
        this.includeOutput = b.includeOutput;
        this.outputTransform = b.outputTransform;
        this.includeErrors = b.includeErrors;
        this.overrides = List.copyOf(b.overrides);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean includeInput() {
        return includeInput;
    }

    public Function<Object, Object> inputTransform() {
        return inputTransform;
    }

    public boolean includeOutput() {
        return includeOutput;
    }

    public Function<Object, Object> outputTransform() {
        return outputTransform;
    }

    public boolean includeErrors() {
        return includeErrors;
    }

    public List<OperationOverride> overrides() {
        return overrides;
    }

    /** Builder for {@link ContentConfig}. */
    public static final class Builder {
        private boolean includeInput = true;
        private Function<Object, Object> inputTransform;
        private boolean includeOutput = true;
        private Function<Object, Object> outputTransform;
        private boolean includeErrors = true;
        private final List<OperationOverride> overrides = new ArrayList<>();

        public Builder input(boolean include) {
            this.includeInput = include;
            return this;
        }

        public Builder inputTransform(Function<Object, Object> transform) {
            this.inputTransform = transform;
            this.includeInput = true;
            return this;
        }

        public Builder output(boolean include) {
            this.includeOutput = include;
            return this;
        }

        public Builder outputTransform(Function<Object, Object> transform) {
            this.outputTransform = transform;
            this.includeOutput = true;
            return this;
        }

        /**
         * Whether per-operation errors ({@code operations[].error}, {@code operationsByName.<name>.error}) are emitted.
         * Default {@code true}. {@code false} does <em>not</em> hide the execution-level {@code error} or its message;
         * see the class documentation.
         *
         * @param include {@code false} to omit per-operation errors
         * @return this builder
         */
        public Builder includeErrors(boolean include) {
            this.includeErrors = include;
            return this;
        }

        public Builder addOverride(OperationOverride override) {
            this.overrides.add(override);
            return this;
        }

        public ContentConfig build() {
            return new ContentConfig(this);
        }
    }
}
