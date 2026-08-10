// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import java.util.Objects;
import java.util.function.Consumer;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.dag.internal.DagContextImpl;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Static entry points for the experimental DAG extension operation. */
@Experimental
public final class DagOperations {
    private DagOperations() {}

    /** Declares and runs a DAG using the current durable extension context. */
    public static DagResult dag(String name, Consumer<DagContext> register) {
        return dagAsync(name, register).get();
    }

    /** Declares and runs a configured DAG using the current durable extension context. */
    public static DagResult dag(String name, Consumer<DagContext> register, DagConfig config) {
        return dagAsync(name, register, config).get();
    }

    /** Asynchronously declares and runs a DAG using the current durable extension context. */
    public static DurableFuture<DagResult> dagAsync(String name, Consumer<DagContext> register) {
        return dagAsync(name, register, DagConfig.builder().build());
    }

    /** Asynchronously declares and runs a configured DAG using the current durable extension context. */
    public static DurableFuture<DagResult> dagAsync(String name, Consumer<DagContext> register, DagConfig config) {
        Objects.requireNonNull(register, "register cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        ParameterValidator.validateOperationName(name);

        var context = ExtensionContext.getCurrentContext();
        var dagContext = DagContextImpl.registerAndValidate(register);
        return DagContextImpl.start(context, context.reserve(name), dagContext, config);
    }
}
