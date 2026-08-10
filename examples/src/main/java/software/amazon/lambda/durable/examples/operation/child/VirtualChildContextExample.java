// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.child;

import static software.amazon.lambda.durable.logging.DurableLogger.getLogger;
import static software.amazon.lambda.durable.operation.DurableContextOperation.runInChildContext;
import static software.amazon.lambda.durable.operation.DurableContextOperation.runInChildContextAsync;
import static software.amazon.lambda.durable.operation.DurableStepOperation.step;

import java.time.Duration;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.operation.DurableContextOperation.RunInChildContextConfig;
import software.amazon.lambda.durable.operation.DurableWaitOperation;

/**
 * Example demonstrating virtual child context workflows with the Durable Execution SDK.
 *
 * <p>This handler runs three concurrent child contexts using {@code runInChildContextAsync}:
 *
 * <ol>
 *   <li><b>Order validation</b> — performs a step then suspends via {@code DurableWaitOperation.wait()} before
 *       completing
 *   <li><b>Inventory check</b> — performs a step then suspends via {@code DurableWaitOperation.wait()} before
 *       completing
 *   <li><b>Shipping estimate</b> — nests another child context inside it to demonstrate hierarchical contexts
 * </ol>
 *
 * <p>All three child contexts run concurrently. Results are collected with {@link DurableFuture#allOf} and combined
 * into a summary string.
 */
public class VirtualChildContextExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input) {
        var name = input.getName();
        getLogger().info("Starting child context workflow for {}", name);

        // Child context 1: Order validation — step + wait + step
        var orderFuture = runInChildContextAsync(
                "order-validation",
                String.class,
                () -> {
                    var prepared = step("prepare-order", String.class, () -> "Order for " + name);
                    getLogger().info("Order prepared, waiting for validation");

                    DurableWaitOperation.wait("validation-delay", Duration.ofSeconds(5));

                    return step("validate-order", String.class, () -> prepared + " [validated]");
                },
                RunInChildContextConfig.builder().isVirtual(true).build());

        // Child context 2: Inventory check — step + wait + step
        var inventoryFuture = runInChildContextAsync(
                "inventory-check",
                String.class,
                () -> {
                    var stock = step("check-stock", String.class, () -> "Stock available for " + name);
                    getLogger().info("Stock checked, waiting for confirmation");

                    DurableWaitOperation.wait("confirmation-delay", Duration.ofSeconds(3));

                    return step("confirm-inventory", String.class, () -> stock + " [confirmed]");
                },
                RunInChildContextConfig.builder().isVirtual(true).build());

        // Child context 3: Shipping estimate — nests a child context inside it
        var shippingFuture = runInChildContextAsync(
                "shipping-estimate",
                String.class,
                () -> {
                    var baseRate = step("calculate-base-rate", String.class, () -> "Base rate for " + name);

                    // Nested child context: calculate regional adjustment
                    var adjustment = runInChildContext(
                            "regional-adjustment",
                            String.class,
                            () -> step("lookup-region", String.class, () -> baseRate + " + regional adjustment"),
                            RunInChildContextConfig.builder().isVirtual(true).build());

                    return step("finalize-shipping", String.class, () -> adjustment + " [shipping ready]");
                },
                RunInChildContextConfig.builder().isVirtual(true).build());

        // Collect all results using allOf
        getLogger().info("Waiting for all child contexts to complete");
        var results = DurableFuture.allOf(orderFuture, inventoryFuture, shippingFuture);

        // Combine into summary
        var summary = String.join(" | ", results);
        getLogger().info("All child contexts complete: {}", summary);

        return summary;
    }
}
