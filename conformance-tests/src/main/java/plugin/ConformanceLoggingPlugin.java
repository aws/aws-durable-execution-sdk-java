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
 * Shared instrumentation plugin for the plugin conformance suite.
 *
 * <p>Emits lifecycle log lines with a configurable prefix (e.g. {@code CONFPLUGIN}, {@code CONFPLUGIN-A}) so one
 * plugin — or two, for the multiple-plugins case — can be registered on a handler. Operation- and attempt-level hooks
 * are filtered to step-type operations to match the requirement vocabulary. All lines are emitted from the real SDK
 * plugin hooks; nothing is hand-rolled.
 */
@SuppressWarnings("deprecation")
public class ConformanceLoggingPlugin implements DurableExecutionPlugin {

    private final String prefix;

    public ConformanceLoggingPlugin(String prefix) {
        this.prefix = prefix;
    }

    private static boolean isStep(String type) {
        return "STEP".equals(type);
    }

    @Override
    public void onInvocationStart(InvocationInfo info) {
        System.out.println(prefix + " invocation-start first=" + info.isFirstInvocation());
    }

    @Override
    public void onInvocationEnd(InvocationEndInfo info) {
        System.out.println(prefix + " invocation-end status=" + info.invocationStatus().name());
    }

    @Override
    public void onOperationStart(OperationInfo info) {
        if (isStep(info.type())) {
            System.out.println(prefix + " operation-start op=" + info.id());
        }
    }

    @Override
    public void onOperationEnd(OperationEndInfo info) {
        if (isStep(info.type())) {
            System.out.println(prefix + " operation-end op=" + info.id() + " status=" + info.status());
        }
    }

    @Override
    public void onUserFunctionStart(UserFunctionStartInfo info) {
        if (isStep(info.type()) && info.attempt() != null) {
            System.out.println(prefix + " attempt-start n=" + info.attempt() + " op=" + info.id());
        }
    }

    @Override
    public void onUserFunctionEnd(UserFunctionEndInfo info) {
        if (isStep(info.type()) && info.attempt() != null) {
            String outcome = info.succeeded() ? "SUCCEEDED" : "FAILED";
            System.out.println(
                    prefix + " attempt-end n=" + info.attempt() + " outcome=" + outcome + " op=" + info.id());
        }
    }
}
