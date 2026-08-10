// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.step;

import static software.amazon.lambda.durable.operation.DurableStepOperation.step;

import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;

/**
 * Simple example demonstrating basic step execution with the Durable Execution SDK.
 *
 * <p>This handler processes a greeting request through three sequential steps:
 *
 * <ol>
 *   <li>Create greeting message
 *   <li>Transform to uppercase
 *   <li>Add punctuation
 * </ol>
 */
public class SimpleStepExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input) {
        // Step 1: Create greeting
        var greeting = step("create-greeting", String.class, () -> "Hello, " + input.getName());

        // Step 2: Transform to uppercase
        var uppercase = step("to-uppercase", String.class, () -> greeting.toUpperCase());

        // Step 3: Add punctuation
        var result = step("add-punctuation", String.class, () -> uppercase + "!");

        return result;
    }
}
