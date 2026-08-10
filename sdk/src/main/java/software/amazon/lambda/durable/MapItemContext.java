// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import software.amazon.lambda.durable.model.SafeCloseable;

/** Metadata for the map item function active on the current SDK-managed thread. */
public final class MapItemContext {
    private static final OperationContextStorage<MapItemContext> CURRENT =
            new OperationContextStorage<>("MapItemContext");

    private final int index;

    private MapItemContext(int index) {
        this.index = index;
    }

    /** Returns the map item context attached to the current SDK-managed thread. */
    public static MapItemContext getCurrentContext() {
        return CURRENT.getCurrentContext();
    }

    /** Returns the zero-based index of the current map item. */
    public int getIndex() {
        return index;
    }

    static SafeCloseable attach(int index) {
        return CURRENT.attach(new MapItemContext(index));
    }
}
