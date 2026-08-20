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
 * <p>Emits lifecycle log lines with a configurable prefix (e.g. {@code CONFPLUGIN}, {@code CONFPLUGIN-A}) so one plugin
 * — or two, for the multiple-plugins case — can be registered on a handler. Operation- and attempt-level hooks are
 * filtered to step-type operations to match the requirement vocabulary. All lines are emitted from the real SDK plugin
 * hooks; nothing is hand-rolled.
 */
public class ConformanceLoggingPlugin implements DurableExecutionPlugin {

    private final String prefix;

    /** Captured from onInvocationStart; read by later hooks that may run on other threads. */
    private volatile String executionArn;

    public ConformanceLoggingPlugin(String prefix) {
        this.prefix = prefix;
    }

    private static boolean isStep(String type) {
        return "STEP".equals(type);
    }

    /** Returns {@code , "durableExecutionArn": "<arn>"} when captured, otherwise an empty string. */
    private String arnField() {
        return executionArn == null ? "" : String.format(", \"durableExecutionArn\": \"%s\"", executionArn);
    }

    @Override
    public void onInvocationStart(InvocationInfo info) {
        this.executionArn = info.durableExecutionArn();
        System.out.println(String.format(
                "{\"plugin\": \"%s\", \"hook\": \"invocation-start\", \"first\": %b%s}",
                prefix, info.isFirstInvocation(), arnField()));
    }

    @Override
    public void onInvocationEnd(InvocationEndInfo info) {
        System.out.println(String.format(
                "{\"plugin\": \"%s\", \"hook\": \"invocation-end\", \"status\": \"%s\"%s}",
                prefix, info.invocationStatus().name(), arnField()));
    }

    @Override
    public void onOperationStart(OperationInfo info) {
        if (isStep(info.type())) {
            System.out.println(String.format(
                    "{\"plugin\": \"%s\", \"hook\": \"operation-start\", \"op\": \"%s\"%s}",
                    prefix, info.id(), arnField()));
        }
    }

    @Override
    public void onOperationEnd(OperationEndInfo info) {
        if (isStep(info.type())) {
            System.out.println(String.format(
                    "{\"plugin\": \"%s\", \"hook\": \"operation-end\", \"op\": \"%s\", \"status\": \"%s\"%s}",
                    prefix, info.id(), info.status(), arnField()));
        }
    }

    @Override
    public void onUserFunctionStart(UserFunctionStartInfo info) {
        if (isStep(info.type()) && info.attempt() != null) {
            System.out.println(String.format(
                    "{\"plugin\": \"%s\", \"hook\": \"attempt-start\", \"n\": %d, \"op\": \"%s\"%s}",
                    prefix, info.attempt(), info.id(), arnField()));
        }
    }

    @Override
    public void onUserFunctionEnd(UserFunctionEndInfo info) {
        if (isStep(info.type()) && info.attempt() != null) {
            String outcome = info.succeeded() ? "SUCCEEDED" : "FAILED";
            System.out.println(String.format(
                    "{\"plugin\": \"%s\", \"hook\": \"attempt-end\", \"n\": %d, \"outcome\": \"%s\", \"op\": \"%s\"%s}",
                    prefix, info.attempt(), outcome, info.id(), arnField()));
        }
    }
}
