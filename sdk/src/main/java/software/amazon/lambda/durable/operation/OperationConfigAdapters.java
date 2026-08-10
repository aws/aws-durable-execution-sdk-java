// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import software.amazon.lambda.durable.config.RunInChildContextConfig;

final class OperationConfigAdapters {
    private OperationConfigAdapters() {}

    static RunInChildContextConfig toLegacy(DurableContextOperation.RunInChildContextConfig config) {
        return RunInChildContextConfig.builder()
                .serDes(config.serDes())
                .isVirtual(config.isVirtual())
                .build();
    }
}
