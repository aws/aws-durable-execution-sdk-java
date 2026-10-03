// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import com.amazonaws.services.lambda.runtime.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Captures optional invocation data while retaining older visible Lambda Context API compatibility. */
final class RuntimeTraceHeader {
    private static final Logger logger = LoggerFactory.getLogger(RuntimeTraceHeader.class);

    private RuntimeTraceHeader() {}

    static String capture(Context context) {
        if (context == null) return null;
        try {
            return context.getXrayTraceId();
        } catch (NoSuchMethodError | AbstractMethodError unavailable) {
            logger.debug("Lambda Context has no X-Ray accessor; retaining ordinary Lambda trace carriers");
            return null;
        }
    }
}
