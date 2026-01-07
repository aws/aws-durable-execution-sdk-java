// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableHandler;

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
    public String handleRequest(GreetingRequest input, DurableContext context) {
        // Step 1: Create greeting
        var greeting = context.step("create-greeting", String.class, () -> "Hello, " + input.getName());

        // Step 2: Transform to uppercase
        var uppercase = context.step("to-uppercase", String.class, () -> greeting.toUpperCase());

        // Step 3: Add punctuation
        var result = context.step("add-punctuation", String.class, () -> uppercase + "!");

        return result;
    }
}
