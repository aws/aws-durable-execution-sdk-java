// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;
import software.amazon.lambda.durable.plugin.UserFunctionEndInfo;
import software.amazon.lambda.durable.plugin.UserFunctionStartInfo;

/**
 * Instrumentation plugin whose every hook logs a line and then throws.
 *
 * <p>Used by requirement 10-4 to verify the SDK isolates plugin exceptions: each hook must run (log its line) and the
 * thrown exception must be swallowed by the SDK so the execution result and history are identical to running without
 * the plugin. Operation- and attempt-level hooks are filtered to step-type operations.
 */
@SuppressWarnings("deprecation")
public class FaultyConformancePlugin implements DurableExecutionPlugin {

    private static boolean isStep(String type) {
        return "STEP".equals(type);
    }

    @Override
    public void onInvocationStart(InvocationInfo info) {
        System.out.println("CONFPLUGIN faulty invocation-start");
        throw new RuntimeException("faulty invocation-start");
    }

    @Override
    public void onInvocationEnd(InvocationEndInfo info) {
        System.out.println("CONFPLUGIN faulty invocation-end");
        throw new RuntimeException("faulty invocation-end");
    }

    @Override
    public void onOperationStart(OperationInfo info) {
        if (isStep(info.type())) {
            System.out.println("CONFPLUGIN faulty operation-start");
            throw new RuntimeException("faulty operation-start");
        }
    }

    @Override
    public void onOperationEnd(OperationEndInfo info) {
        if (isStep(info.type())) {
            System.out.println("CONFPLUGIN faulty operation-end");
            throw new RuntimeException("faulty operation-end");
        }
    }

    @Override
    public void onUserFunctionStart(UserFunctionStartInfo info) {
        if (isStep(info.type())) {
            System.out.println("CONFPLUGIN faulty attempt-start");
            throw new RuntimeException("faulty attempt-start");
        }
    }

    @Override
    public void onUserFunctionEnd(UserFunctionEndInfo info) {
        if (isStep(info.type())) {
            System.out.println("CONFPLUGIN faulty attempt-end");
            throw new RuntimeException("faulty attempt-end");
        }
    }
}
