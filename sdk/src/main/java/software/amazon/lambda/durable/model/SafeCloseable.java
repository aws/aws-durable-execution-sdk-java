// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

/** A resource that can be safely closed, similar to {@link java.io.Closeable} but without throwing IOException. */
public interface SafeCloseable extends AutoCloseable {
    void close();
}
