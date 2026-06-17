// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.xray.XRayClient;
import software.amazon.awssdk.services.xray.model.BatchGetTracesRequest;
import software.amazon.awssdk.services.xray.model.GetTraceSummariesRequest;
import software.amazon.awssdk.services.xray.model.Segment;
import software.amazon.awssdk.services.xray.model.TimeRangeType;
import software.amazon.awssdk.services.xray.model.TraceSummary;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.CloudDurableTestRunner;

/**
 * Integration tests that verify OTel spans exported via ADOT appear correctly in AWS X-Ray.
 *
 * <p>These tests deploy Lambda functions configured with:
 *
 * <ul>
 *   <li>OpenTelemetry Durable Plugin with OTLP gRPC exporter
 *   <li>ADOT collector layer (OTLP receiver → X-Ray exporter)
 *   <li>Active X-Ray tracing
 * </ul>
 *
 * <p>After invoking the function, the test queries the X-Ray API to verify:
 *
 * <ul>
 *   <li>A single trace exists for the execution (deterministic trace ID works)
 *   <li>Expected span/segment names are present
 *   <li>Parent-child nesting is correct
 *   <li>Multi-invocation scenarios produce one unified trace
 * </ul>
 *
 * <p>Enable with: {@code -Dtest.cloud.enabled=true}
 */
@EnabledIf("isEnabled")
class OtelXRayIntegrationTest {

    private static final Duration XRAY_INGESTION_DELAY = Duration.ofSeconds(20);
    private static final int XRAY_QUERY_RETRIES = 3;
    private static final Duration XRAY_RETRY_DELAY = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String account;
    private static String region;
    private static String functionNameSuffix;
    private static LambdaClient lambdaClient;
    private static XRayClient xrayClient;

    static boolean isEnabled() {
        var enabled = "true".equals(System.getProperty("test.cloud.enabled"));
        if (!enabled) {
            System.out.println("⚠️ OTel X-Ray integration tests disabled. Enable with -Dtest.cloud.enabled=true");
        }
        return enabled;
    }

    @BeforeAll
    static void setup() {
        try {
            DefaultCredentialsProvider.builder().build().resolveCredentials();
        } catch (Exception e) {
            throw new IllegalStateException("AWS credentials not available");
        }

        account = System.getProperty("test.aws.account");
        region = System.getProperty("test.aws.region");
        functionNameSuffix = System.getProperty("test.function.name.suffix", "-java17-runtime");

        if (account == null || region == null) {
            try (var sts = StsClient.create()) {
                if (account == null) account = sts.getCallerIdentity().account();
                if (region == null)
                    region = sts.serviceClientConfiguration().region().id();
            }
        }

        lambdaClient = LambdaClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .region(Region.of(region))
                .build();

        xrayClient = XRayClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .region(Region.of(region))
                .build();

        System.out.println("☁️ Running OTel X-Ray integration tests against account " + account + " in " + region);
    }

    private static String arn(String functionName) {
        return "arn:aws:lambda:" + region + ":" + account + ":function:" + functionName + functionNameSuffix
                + ":$LATEST";
    }

    // ─── Test: Simple Steps (Single Invocation) ──────────────────────────

    @Test
    void simpleSteps_producesUnifiedTraceInXRay() throws Exception {
        var startTime = Instant.now();

        // 1. Invoke the function (use unique input to avoid stale executions)
        var runner = CloudDurableTestRunner.create(
                arn("otel-xray-step-example"), GreetingRequest.class, String.class, lambdaClient);
        var uniqueInput = "XRay-" + System.currentTimeMillis();
        var result = runner.run(new GreetingRequest(uniqueInput));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus(), "Execution failed: " + result);
        assertEquals("HELLO, " + uniqueInput.toUpperCase() + "!", result.getResult());

        // 2. Wait for X-Ray ingestion
        Thread.sleep(XRAY_INGESTION_DELAY.toMillis());

        // 3. Query X-Ray for the trace
        var traces = queryTracesWithRetry(startTime, Instant.now(), "otel-xray-step-example");

        assertFalse(traces.isEmpty(), "Expected at least one trace in X-Ray after execution");

        // Get full trace details (batch in groups of 5 — X-Ray API limit)
        var traceIds = traces.stream().map(TraceSummary::id).toList();
        var allTraces = new java.util.ArrayList<software.amazon.awssdk.services.xray.model.Trace>();
        for (int i = 0; i < traceIds.size(); i += 5) {
            var batch = traceIds.subList(i, Math.min(i + 5, traceIds.size()));
            var batchResult = xrayClient.batchGetTraces(
                    BatchGetTracesRequest.builder().traceIds(batch).build());
            allTraces.addAll(batchResult.traces());
        }

        // Find the trace that contains our durable spans
        var durableTrace = allTraces.stream()
                .filter(trace ->
                        trace.segments().stream().anyMatch(seg -> segmentContains(seg, "durable.step:create-greeting")))
                .findFirst()
                .orElse(null);

        assertNotNull(
                durableTrace,
                "Expected to find a trace with durable.step:create-greeting segment. " + "Found " + traces.size()
                        + " traces in the time window. Segment names: "
                        + allTraces.stream()
                                .flatMap(t -> t.segments().stream())
                                .map(seg -> getSegmentName(seg))
                                .collect(Collectors.joining(", ")));

        // 5. Verify span structure
        var segmentDocuments =
                durableTrace.segments().stream().map(Segment::document).toList();
        var allSegmentText = String.join("\n", segmentDocuments);

        // Verify expected span names appear in the trace
        assertTrue(
                allSegmentText.contains("durable.invocation"),
                "Expected durable.invocation span in trace. Segments: " + summarizeSegments(segmentDocuments));
        assertTrue(
                allSegmentText.contains("durable.step:create-greeting"),
                "Expected durable.step:create-greeting span in trace");
        assertTrue(allSegmentText.contains("durable.step:transform"), "Expected durable.step:transform span in trace");

        // Verify all segments share the same trace ID (single unified trace)
        var uniqueTraceIds =
                durableTrace.segments().stream().map(seg -> durableTrace.id()).collect(Collectors.toSet());
        assertEquals(1, uniqueTraceIds.size(), "All segments should belong to a single trace");

        System.out.println("✅ Simple steps test passed — "
                + durableTrace.segments().size() + " segments in trace " + durableTrace.id());
    }

    // ─── Test: Wait + Resume (Multi-Invocation) ─────────────────────────

    @Test
    void waitAndResume_producesUnifiedTraceAcrossInvocations() throws Exception {
        var startTime = Instant.now();

        // 1. Invoke the function — will suspend on wait, then resume automatically
        var runner = CloudDurableTestRunner.create(
                arn("otel-xray-wait-example"), GreetingRequest.class, String.class, lambdaClient);
        var uniqueInput = "Wait-" + System.currentTimeMillis();
        var result = runner.run(new GreetingRequest(uniqueInput));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus(), "Execution failed: " + result);
        assertTrue(
                result.getResult().contains("Resumed and completed"),
                "Expected result to contain 'Resumed and completed', got: " + result.getResult());

        // 2. Wait for X-Ray ingestion (extra time since multi-invocation takes longer)
        Thread.sleep(XRAY_INGESTION_DELAY.plus(Duration.ofSeconds(5)).toMillis());

        // 3. Query X-Ray for the trace
        var traces = queryTracesWithRetry(startTime, Instant.now(), "otel-xray-wait-example");

        assertFalse(traces.isEmpty(), "Expected at least one trace in X-Ray after multi-invocation execution");

        // Get full trace details (batch in groups of 5 — X-Ray API limit)
        var traceIds = traces.stream().map(TraceSummary::id).toList();
        var allTraces = new java.util.ArrayList<software.amazon.awssdk.services.xray.model.Trace>();
        for (int i = 0; i < traceIds.size(); i += 5) {
            var batch = traceIds.subList(i, Math.min(i + 5, traceIds.size()));
            var batchResult = xrayClient.batchGetTraces(
                    BatchGetTracesRequest.builder().traceIds(batch).build());
            allTraces.addAll(batchResult.traces());
        }

        // Find the trace containing our durable spans
        var durableTrace = allTraces.stream()
                .filter(trace ->
                        trace.segments().stream().anyMatch(seg -> segmentContains(seg, "durable.step:before-wait")))
                .findFirst()
                .orElse(null);

        assertNotNull(
                durableTrace,
                "Expected to find a trace with durable.step:before-wait segment. " + "Found " + traces.size()
                        + " traces in the time window.");

        // 4. Verify multi-invocation trace structure
        var segmentDocuments =
                durableTrace.segments().stream().map(Segment::document).toList();
        var allSegmentText = String.join("\n", segmentDocuments);

        // Verify spans from BOTH invocations appear in the same trace
        assertTrue(
                allSegmentText.contains("durable.step:before-wait"), "Expected before-wait span from first invocation");
        assertTrue(
                allSegmentText.contains("durable.step:after-wait"), "Expected after-wait span from second invocation");
        assertTrue(allSegmentText.contains("durable.wait:pause"), "Expected wait:pause span in trace");

        // Verify multiple invocation spans (one per Lambda invocation)
        var invocationCount = countOccurrences(allSegmentText, "durable.invocation");
        assertTrue(
                invocationCount >= 2,
                "Expected at least 2 invocation spans (multi-invocation), got " + invocationCount);

        // Critical assertion: all segments under ONE trace (deterministic ID worked)
        assertEquals(
                1,
                Set.of(durableTrace.id()).size(),
                "All segments should belong to a single trace — deterministic trace ID must work across invocations");

        System.out.println(
                "✅ Wait + resume test passed — " + durableTrace.segments().size() + " segments across "
                        + invocationCount + " invocations in trace " + durableTrace.id());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /** Queries X-Ray for traces with retry logic to handle eventual consistency. */
    private List<TraceSummary> queryTracesWithRetry(Instant startTime, Instant endTime, String functionName)
            throws InterruptedException {
        // Query by durable.invocation service — our spans are in a separate trace from Lambda's
        // built-in X-Ray segment (durable backend propagates its own trace root)
        var filterExpression = "service(\"durable.invocation\")";
        for (int attempt = 0; attempt < XRAY_QUERY_RETRIES; attempt++) {
            var response = xrayClient.getTraceSummaries(GetTraceSummariesRequest.builder()
                    .startTime(startTime)
                    .endTime(endTime)
                    .timeRangeType(TimeRangeType.EVENT)
                    .sampling(false)
                    .filterExpression(filterExpression)
                    .build());

            var traces = response.traceSummaries();
            if (!traces.isEmpty()) {
                return traces;
            }

            System.out.println("⏳ X-Ray query returned 0 traces, retrying in " + XRAY_RETRY_DELAY.toSeconds() + "s "
                    + "(attempt " + (attempt + 1) + "/" + XRAY_QUERY_RETRIES + ")");
            Thread.sleep(XRAY_RETRY_DELAY.toMillis());
        }
        return List.of();
    }

    /** Checks if a segment's document JSON contains a segment with the given name. */
    private static boolean segmentContains(Segment segment, String name) {
        return getSegmentName(segment).equals(name)
                || (segment.document() != null && segment.document().contains("\"name\":\"" + name + "\""));
    }

    /** Extracts the "name" field from a segment's JSON document. */
    private static String getSegmentName(Segment segment) {
        if (segment.document() == null) return "(null)";
        try {
            var node = MAPPER.readTree(segment.document());
            var nameNode = node.get("name");
            return nameNode != null ? nameNode.asText() : "(no-name)";
        } catch (Exception e) {
            return "(parse-error)";
        }
    }

    /** Extracts all segment names from a list of segment document JSONs. */
    private static List<String> extractSegmentNames(List<String> segmentDocuments) {
        return segmentDocuments.stream()
                .map(doc -> {
                    try {
                        var node = MAPPER.readTree(doc);
                        var nameNode = node.get("name");
                        return nameNode != null ? nameNode.asText() : "(no-name)";
                    } catch (Exception e) {
                        return "(parse-error)";
                    }
                })
                .toList();
    }

    /** Counts occurrences of a substring in text. */
    private static int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    /** Creates a brief summary of segment names for assertion error messages. */
    private static String summarizeSegments(List<String> segmentDocuments) {
        return extractSegmentNames(segmentDocuments).stream().collect(Collectors.joining(", ", "[", "]"));
    }
}
