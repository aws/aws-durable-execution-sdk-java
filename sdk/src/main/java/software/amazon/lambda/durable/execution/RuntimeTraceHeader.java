// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import com.amazonaws.services.lambda.runtime.Context;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Captures optional invocation data while retaining older visible Lambda Context API compatibility. */
final class RuntimeTraceHeader {
    private static final Logger logger = LoggerFactory.getLogger(RuntimeTraceHeader.class);

    private RuntimeTraceHeader() {}

    static String capture(Context context) {
        if (context == null) return null;
        try {
            // Check the actual visible interface first: a legacy class may independently declare the same helper.
            if (!hasAccessorApi()) return null;
            var accessor = context.getClass().getMethod("getXrayTraceId");
            // Lambda Core 1.4's neutral default is not an invocation-local carrier.
            if (accessor.getDeclaringClass() == Context.class
                    || accessor.getReturnType() != String.class
                    || Modifier.isStatic(accessor.getModifiers())) return null;
            var header = context.getXrayTraceId();
            return header == null ? "" : header;
        } catch (Throwable failure) {
            rethrowFatal(failure);
            // A failed available override must not borrow another invocation's global trace or sampling.
            logger.debug("Lambda Context X-Ray capture failed; treating invocation header as absent");
            return "";
        }
    }

    private static boolean hasAccessorApi() {
        try {
            Context.class.getMethod("getXrayTraceId");
            return true;
        } catch (NoSuchMethodException unavailable) {
            return false;
        }
    }

    @SuppressWarnings("removal")
    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var cause = failure;
        while (seen.add(cause)) {
            if (cause instanceof VirtualMachineError fatal) throw fatal;
            if (cause instanceof ThreadDeath fatal) throw fatal;
            if (!(cause instanceof CompletionException
                    || cause instanceof ExecutionException
                    || cause instanceof InvocationTargetException
                    || cause instanceof UndeclaredThrowableException)) return;
            try {
                cause = cause.getCause();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable unreadableCause) {
                return;
            }
            if (cause == null) return;
        }
    }
}
