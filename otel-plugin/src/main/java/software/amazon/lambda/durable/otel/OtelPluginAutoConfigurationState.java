// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

final class OtelPluginAutoConfigurationState {

    private static final String INSTALLED_PROPERTY =
            "software.amazon.lambda.durable.otel.autoConfigurationCustomizerProviderInstalled";

    private OtelPluginAutoConfigurationState() {}

    static boolean isInstalled() {
        return Boolean.getBoolean(INSTALLED_PROPERTY);
    }

    static void markInstalled() {
        System.setProperty(INSTALLED_PROPERTY, Boolean.TRUE.toString());
    }

    static void resetInstalledForTest() {
        System.clearProperty(INSTALLED_PROPERTY);
    }
}
