// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Keeps thread-bound OTel scopes with their user-function owner, including after invocation End. */
final class UserFunctionScopes {
    private final ThreadLocal<LinkedHashMap<String, Scope>> scopes = ThreadLocal.withInitial(LinkedHashMap::new);

    void put(String key, Scope scope) {
        scopes.get().put(key, scope);
    }

    void close(String key) {
        var owned = scopes.get();
        var scope = owned.remove(key);
        if (owned.isEmpty()) scopes.remove();
        if (scope != null) scope.close();
    }

    /** Closes abandoned scopes only on this thread; running foreign owners retain their late End cleanup. */
    void closeCurrentThread() {
        var owned = scopes.get();
        scopes.remove();
        var open = new ArrayList<>(owned.values());
        for (var index = open.size() - 1; index >= 0; index--) {
            open.get(index).close();
        }
    }
}
