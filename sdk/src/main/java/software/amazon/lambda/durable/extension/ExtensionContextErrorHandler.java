// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

/** Translates an extension CONTEXT failure when its original exception cannot be reconstructed. */
@FunctionalInterface
public interface ExtensionContextErrorHandler {
    /** Returns the exception exposed by the durable future. */
    Throwable translate(ExtensionContextFailure failure);
}
