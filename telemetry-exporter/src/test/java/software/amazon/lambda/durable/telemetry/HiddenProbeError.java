// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

/**
 * Template for the hidden-class test in {@link ErrorNameTest}: its bytes are defined again as a hidden class, whose
 * name gets a {@code /0x…} suffix.
 */
public class HiddenProbeError extends RuntimeException {
    public HiddenProbeError() {
        super("hidden");
    }
}
