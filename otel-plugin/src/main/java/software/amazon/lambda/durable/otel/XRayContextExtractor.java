// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.contrib.awsxray.propagator.AwsXrayPropagator;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts OTel trace context from the AWS X-Ray {@code _X_AMZN_TRACE_ID} environment variable using the official
 * OpenTelemetry AWS X-Ray Propagator.
 *
 * <p>Lambda runtime sets this environment variable with the X-Ray trace header for each invocation. This extractor
 * reads it and uses the official {@link AwsXrayPropagator} to parse it into an OTel-compatible {@link Context}.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public class XRayContextExtractor implements ContextExtractor {

    private static final Logger logger = LoggerFactory.getLogger(XRayContextExtractor.class);
    private static final String XRAY_ENV_VAR = "_X_AMZN_TRACE_ID";
    private static final String XRAY_HEADER_KEY = "X-Amzn-Trace-Id";
    private static final AwsXrayPropagator PROPAGATOR = AwsXrayPropagator.getInstance();

    /** A TextMapGetter that reads the X-Ray trace header from the Lambda environment variable. */
    private static final TextMapGetter<String> ENV_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(String carrier) {
            return Collections.singletonList(XRAY_HEADER_KEY);
        }

        @Override
        public String get(String carrier, String key) {
            if (XRAY_HEADER_KEY.equalsIgnoreCase(key)) {
                return carrier;
            }
            return null;
        }
    };

    @Override
    public Context extract() {
        var traceHeader = System.getenv(XRAY_ENV_VAR);
        if (traceHeader == null || traceHeader.isEmpty()) {
            logger.debug("No X-Ray trace header found in environment");
            return Context.root();
        }

        return PROPAGATOR.extract(Context.root(), traceHeader, ENV_GETTER);
    }
}
