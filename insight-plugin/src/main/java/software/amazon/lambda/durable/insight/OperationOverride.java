// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.function.Function;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Per-operation override controlling inclusion and result transformation, matched by {@code operationName}.
 *
 * <p>Mirrors the JS {@code OperationOverride}. The {@code result} transform receives the operation's checkpointed
 * result as a <em>detached, JSON-compatible</em> value (from {@code OperationChangeItemInfo.result()}): a former POJO
 * arrives as a {@code Map}, a Java-time type as its JSON representation (e.g. an {@code Instant} as an ISO-8601
 * {@code String}), and the raw string is passed through only when the checkpoint is not valid JSON. Mutating the
 * argument is safe, and a transform that throws omits the field (the failure is logged) rather than leaking the raw
 * value or failing the execution. An {@code exclude} override drops the operation entirely.
 */
@Experimental
public final class OperationOverride {
    private final String operationName;
    private final boolean exclude;
    private final Function<Object, Object> result;

    private OperationOverride(String operationName, boolean exclude, Function<Object, Object> result) {
        this.operationName = operationName;
        this.exclude = exclude;
        this.result = result;
    }

    public static OperationOverride exclude(String operationName) {
        return new OperationOverride(operationName, true, null);
    }

    public static OperationOverride withResult(String operationName, Function<Object, Object> result) {
        return new OperationOverride(operationName, false, result);
    }

    public String operationName() {
        return operationName;
    }

    public boolean isExclude() {
        return exclude;
    }

    public Function<Object, Object> result() {
        return result;
    }
}
