// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload;

/** Factory methods for built-in payload offloader policies. */
public final class PayloadOffloaders {
    private static final PayloadOffloader DISABLED = new DisabledPayloadOffloader();

    private PayloadOffloaders() {}

    /** Returns a sentinel that forces payloads to remain in the normal inline checkpoint format. */
    public static PayloadOffloader disabled() {
        return DISABLED;
    }

    /** Returns whether the supplied offloader is the disabled sentinel. */
    public static boolean isDisabled(PayloadOffloader offloader) {
        return offloader == DISABLED;
    }

    private static final class DisabledPayloadOffloader implements PayloadOffloader {
        @Override
        public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
            return OffloadedPayload.inline(serializedPayload);
        }

        @Override
        public String load(OffloadedPayload payload, PayloadOffloadContext context) {
            return payload.data();
        }

        @Override
        public String toString() {
            return "PayloadOffloader.disabled()";
        }
    }
}
