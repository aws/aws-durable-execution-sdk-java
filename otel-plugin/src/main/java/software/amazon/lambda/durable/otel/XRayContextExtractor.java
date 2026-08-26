// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceId;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts OTel trace context from the AWS X-Ray {@code _X_AMZN_TRACE_ID} environment variable.
 *
 * <p>This extractor parses the Lambda/X-Ray header and returns the trace ID in OTel format (32 hex chars) along with
 * the parent span ID (16 hex chars). Plugins use it as a fallback parent for Invocation spans; the deterministic
 * Workflow trace is derived separately from the durable execution ARN.
 *
 * <p>X-Ray header format: {@code Root=1-5759e988-bd862e3fe1be46a994272793;Parent=53995c3f42cd8ad8;Sampled=1}
 *
 * <p>The Root field is converted to OTel format by stripping "1-" and removing dashes:
 * {@code 5759e988bd862e3fe1be46a994272793}
 */
public class XRayContextExtractor implements ContextExtractor {

    private static final Logger logger = LoggerFactory.getLogger(XRayContextExtractor.class);
    private static final String XRAY_ENV_VAR = "_X_AMZN_TRACE_ID";
    private static final String XRAY_SYSTEM_PROPERTY = "com.amazonaws.xray.traceHeader";
    private static final Pattern HEX_32 = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern HEX_16 = Pattern.compile("[0-9a-f]{16}");

    @Override
    public ExtractedContext extract() {
        // Try system property first — the Lambda runtime interface client updates this per invocation, so it reflects
        // the current invocation and avoids the JVM's process-lifetime environment-variable caching.
        var traceHeader = System.getProperty(XRAY_SYSTEM_PROPERTY);
        if (traceHeader == null || traceHeader.isEmpty()) {
            // Fallback to the environment variable (non-agent environments, local runs).
            traceHeader = System.getenv(XRAY_ENV_VAR);
        }
        if (traceHeader == null || traceHeader.isEmpty()) {
            logger.debug("No X-Ray trace header found in environment or system properties");
            return null;
        }

        String root = null;
        String parent = null;
        String sampled = null;

        for (var part : traceHeader.split(";")) {
            var eqIdx = part.indexOf('=');
            if (eqIdx <= 0) continue;
            var key = part.substring(0, eqIdx).trim();
            var value = part.substring(eqIdx + 1).trim();

            switch (key) {
                case "Root" -> root = value;
                case "Parent" -> parent = value;
                case "Sampled" -> sampled = value;
            }
        }

        if (root == null) {
            logger.debug("X-Ray header missing Root field: {}", traceHeader);
            return null;
        }

        // Root format: 1-5759e988-bd862e3fe1be46a994272793 → strip "1-" and dashes → 32-char hex OTel trace ID. Reject
        // an all-zero Root: it is well-formed but an invalid trace ID, and reusing it would anchor the execution on an
        // invalid trace.
        var traceId = xrayRootToOtelTraceId(root);
        if (traceId == null || !TraceId.isValid(traceId)) {
            logger.debug("Invalid X-Ray Root field: {}", root);
            return null;
        }

        // Parent is a 16-char hex span ID; may be absent (Root-only header is still usable). Reject an all-zero Parent
        // the same way — it is well-formed but invalid.
        String parentSpanId = null;
        if (parent != null) {
            var normalized = parent.toLowerCase();
            if (HEX_16.matcher(normalized).matches() && SpanId.isValid(normalized)) {
                parentSpanId = normalized;
            }
        }

        // Only Sampled=1 and Sampled=0 are authoritative; anything else (missing or unusable) is undecided.
        var sampling =
                switch (sampled == null ? "" : sampled) {
                    case "1" -> ExtractedContext.Sampling.SAMPLED;
                    case "0" -> ExtractedContext.Sampling.NOT_SAMPLED;
                    default -> ExtractedContext.Sampling.UNDECIDED;
                };

        return new ExtractedContext(traceId, parentSpanId, sampling);
    }

    /**
     * Converts an X-Ray Root value to a 32-char OTel trace ID.
     *
     * @param root e.g. "1-5759e988-bd862e3fe1be46a994272793"
     * @return 32-char lowercase hex string, or null if invalid
     */
    static String xrayRootToOtelTraceId(String root) {
        // Strip "1-" version prefix
        if (!root.startsWith("1-")) {
            return null;
        }
        var withoutVersion = root.substring(2);
        var hex = withoutVersion.replace("-", "").toLowerCase();

        if (hex.length() != 32 || !HEX_32.matcher(hex).matches()) {
            return null;
        }
        return hex;
    }
}
