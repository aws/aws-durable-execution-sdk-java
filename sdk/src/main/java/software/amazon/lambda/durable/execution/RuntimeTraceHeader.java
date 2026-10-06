// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import com.amazonaws.services.lambda.runtime.Context;
import java.lang.reflect.Modifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Captures optional invocation data while retaining older visible Lambda Context API compatibility. */
final class RuntimeTraceHeader {
    private static final Logger logger = LoggerFactory.getLogger(RuntimeTraceHeader.class);

    private RuntimeTraceHeader() {}

    static String capture(Context context) {
        if (context == null) return null;
        try {
            var accessor = context.getClass().getMethod("getXrayTraceId");
            // Lambda Core 1.4 supplies a default returning null even for pre-1.4 Context implementations.
            // Only a runtime override provides an invocation-local carrier. Preserve their ordinary fallback.
            if (accessor.getDeclaringClass() == Context.class
                    || accessor.getReturnType() != String.class
                    || Modifier.isStatic(accessor.getModifiers())) return null;
            // Null denotes no runtime carrier. An override returning no header is authoritative absence.
            var header = context.getXrayTraceId();
            return header == null ? "" : header;
        } catch (NoSuchMethodException | NoSuchMethodError | AbstractMethodError unavailable) {
            logger.debug("Lambda Context has no X-Ray accessor; retaining ordinary Lambda trace carriers");
            return null;
        } catch (RuntimeException unavailable) {
            // Failed invocation-local access must not borrow another invocation's global trace or sampling.
            logger.debug("Lambda Context X-Ray capture failed; treating invocation header as absent");
            return "";
        }
    }
}
