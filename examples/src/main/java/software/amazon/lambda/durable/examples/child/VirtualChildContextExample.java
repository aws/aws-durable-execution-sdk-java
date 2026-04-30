// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.child;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.examples.types.GreetingRequest;

/**
 * Example demonstrating virtual child context workflows with the Durable Execution SDK.
 *
 * <p>This handler runs three concurrent child contexts using {@code runInChildContextAsync}:
 *
 * <ol>
 *   <li><b>Order validation</b> — performs a step then suspends via {@code wait()} before completing
 *   <li><b>Inventory check</b> — performs a step then suspends via {@code wait()} before completing
 *   <li><b>Shipping estimate</b> — nests another child context inside it to demonstrate hierarchical contexts
 * </ol>
 *
 * <p>All three child contexts run concurrently. Results are collected with {@link DurableFuture#allOf} and combined
 * into a summary string.
 */
public class VirtualChildContextExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        var name = input.getName();
        context.getLogger().info("Starting child context workflow for {}", name);

        // Child context 1: Order validation — step + wait + step
        var orderFuture = context.runInChildContextAsync(
                "order-validation",
                String.class,
                child -> {
                    var prepared = child.step("prepare-order", String.class, stepCtx -> "Order for " + name);
                    child.getLogger().info("Order prepared, waiting for validation");

                    child.wait("validation-delay", Duration.ofSeconds(5));

                    return child.step("validate-order", String.class, stepCtx -> prepared + " [validated]");
                },
                RunInChildContextConfig.builder().isVirtual(true).build());

        // Child context 2: Inventory check — step + wait + step
        var inventoryFuture = context.runInChildContextAsync(
                "inventory-check",
                String.class,
                child -> {
                    var stock = child.step("check-stock", String.class, stepCtx -> "Stock available for " + name);
                    child.getLogger().info("Stock checked, waiting for confirmation");

                    child.wait("confirmation-delay", Duration.ofSeconds(3));

                    return child.step("confirm-inventory", String.class, stepCtx -> stock + " [confirmed]");
                },
                RunInChildContextConfig.builder().isVirtual(true).build());

        // Child context 3: Shipping estimate — nests a child context inside it
        var shippingFuture = context.runInChildContextAsync(
                "shipping-estimate",
                String.class,
                child -> {
                    var baseRate = child.step("calculate-base-rate", String.class, stepCtx -> "Base rate for " + name);

                    // Nested child context: calculate regional adjustment
                    var adjustment = child.runInChildContext(
                            "regional-adjustment",
                            String.class,
                            nested -> nested.step(
                                    "lookup-region", String.class, stepCtx -> baseRate + " + regional adjustment"),
                            RunInChildContextConfig.builder().isVirtual(true).build());

                    return child.step("finalize-shipping", String.class, stepCtx -> adjustment + " [shipping ready]");
                },
                RunInChildContextConfig.builder().isVirtual(true).build());

        // Collect all results using allOf
        context.getLogger().info("Waiting for all child contexts to complete");
        var results = DurableFuture.allOf(orderFuture, inventoryFuture, shippingFuture);

        // Combine into summary
        var summary = String.join(" | ", results);
        context.getLogger().info("All child contexts complete: {}", summary);

        return summary;
    }
}
