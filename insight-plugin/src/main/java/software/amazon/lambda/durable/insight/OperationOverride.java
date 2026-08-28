// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.function.Function;

/**
 * Per-operation override controlling inclusion and result transformation, matched by {@code operationName}.
 *
 * <p>Mirrors the JS {@code OperationOverride}. The {@code result} transform receives the operation's checkpointed,
 * JSON-parsed result (from {@code OperationChangeItemInfo.result()}); the raw string is passed through when it is not
 * valid JSON, and a throwing transform omits the field rather than leaking the raw value. An {@code exclude} override
 * drops the operation entirely.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
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
