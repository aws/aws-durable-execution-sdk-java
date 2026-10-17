// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Internal fatal-failure policy for Insight hooks, serializers and transport adapters. */
public final class FatalErrors {
    private FatalErrors() {}

    /** Finds only direct fatal errors and known transport wrappers, not arbitrary business causes. */
    @SuppressWarnings("removal") // ThreadDeath remains supported on the SDK's Java 17 baseline.
    public static Error find(Throwable failure) {
        Set<Throwable> seen = null;
        while (failure != null) {
            if (failure instanceof VirtualMachineError fatal) return fatal;
            if (failure instanceof ThreadDeath fatal) return fatal;
            if (!isTransport(failure)) return null;
            if (seen == null) seen = Collections.newSetFromMap(new IdentityHashMap<>());
            if (!seen.add(failure)) return null;
            try {
                failure = failure.getCause();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                return fatal;
            } catch (Throwable ignored) {
                return null; // A broken non-fatal cause accessor must not break fail-open handling.
            }
        }
        return null;
    }

    private static boolean isTransport(Throwable failure) {
        return failure instanceof CompletionException
                || failure instanceof ExecutionException
                || failure instanceof InvocationTargetException
                || failure instanceof UndeclaredThrowableException
                || failure instanceof JsonProcessingException;
    }

    public static void rethrow(Throwable failure) {
        Error fatal = find(failure);
        if (fatal != null) throw fatal;
    }
}
