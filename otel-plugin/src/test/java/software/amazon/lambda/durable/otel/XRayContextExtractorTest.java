// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class XRayContextExtractorTest {

    @Test
    void extract_withoutEnvVar_returnsNull() {
        // _X_AMZN_TRACE_ID is not set in test environment
        var extractor = new XRayContextExtractor();
        var context = extractor.extract();

        // Should return null when env var is missing
        assertNull(context);
    }

    @Test
    void extract_readsFromSystemProperty_whenEnvVarMissing() {
        // Simulate Lambda runtime setting the system property (env var not set in tests)
        var traceHeader = "Root=1-6a43574f-2cf3140a69b0c8fe165f9503;Parent=53995c3f42cd8ad8;Sampled=1";
        System.setProperty("com.amazonaws.xray.traceHeader", traceHeader);
        try {
            var extractor = new XRayContextExtractor();
            var context = extractor.extract();

            assertNotNull(context, "Should extract context from system property when env var is missing");
            assertEquals("6a43574f2cf3140a69b0c8fe165f9503", context.traceId());
            assertEquals("53995c3f42cd8ad8", context.parentSpanId());
        } finally {
            System.clearProperty("com.amazonaws.xray.traceHeader");
        }
    }

    @Test
    void extract_implementsContextExtractor() {
        // Verify XRayContextExtractor implements the ContextExtractor interface
        ContextExtractor extractor = new XRayContextExtractor();
        // In test env without _X_AMZN_TRACE_ID, returns null
        assertNull(extractor.extract());
    }

    // ─── xrayRootToOtelTraceId: valid inputs ────────────────────────────

    @Test
    void xrayRootToOtelTraceId_validRoot() {
        var result = XRayContextExtractor.xrayRootToOtelTraceId("1-5759e988-bd862e3fe1be46a994272793");
        assertEquals("5759e988bd862e3fe1be46a994272793", result);
    }

    @Test
    void xrayRootToOtelTraceId_validRoot_allZeros() {
        var result = XRayContextExtractor.xrayRootToOtelTraceId("1-00000000-000000000000000000000000");
        assertEquals("00000000000000000000000000000000", result);
    }

    @Test
    void xrayRootToOtelTraceId_validRoot_allFs() {
        var result = XRayContextExtractor.xrayRootToOtelTraceId("1-ffffffff-ffffffffffffffffffffffff");
        assertEquals("ffffffffffffffffffffffffffffffff", result);
    }

    @Test
    void xrayRootToOtelTraceId_validRoot_mixedCase_normalizesToLowercase() {
        var result = XRayContextExtractor.xrayRootToOtelTraceId("1-5759E988-BD862E3FE1BE46A994272793");
        assertEquals("5759e988bd862e3fe1be46a994272793", result);
    }

    @Test
    void xrayRootToOtelTraceId_producesExpected32CharHex() {
        var result = XRayContextExtractor.xrayRootToOtelTraceId("1-67890abc-def01234567890abcdef0123");
        assertNotNull(result);
        assertEquals(32, result.length());
        assertTrue(result.matches("[0-9a-f]{32}"), "Should be 32 lowercase hex chars");
    }

    // ─── xrayRootToOtelTraceId: invalid inputs ──────────────────────────

    @Test
    void xrayRootToOtelTraceId_invalidPrefix() {
        var result = XRayContextExtractor.xrayRootToOtelTraceId("2-5759e988-bd862e3fe1be46a994272793");
        assertNull(result);
    }

    @Test
    void xrayRootToOtelTraceId_noPrefix() {
        var result = XRayContextExtractor.xrayRootToOtelTraceId("5759e988-bd862e3fe1be46a994272793");
        assertNull(result);
    }

    @Test
    void xrayRootToOtelTraceId_wrongLength() {
        var result = XRayContextExtractor.xrayRootToOtelTraceId("1-5759e988-bd862e3fe1be46a9");
        assertNull(result);
    }

    @Test
    void xrayRootToOtelTraceId_tooLong() {
        var result = XRayContextExtractor.xrayRootToOtelTraceId("1-5759e988-bd862e3fe1be46a994272793aabbccdd");
        assertNull(result);
    }

    @Test
    void xrayRootToOtelTraceId_nonHex() {
        var result = XRayContextExtractor.xrayRootToOtelTraceId("1-5759e988-ZZZZZZZZZZZZZZZZZZZZZZZZ");
        assertNull(result);
    }

    @Test
    void xrayRootToOtelTraceId_emptyString() {
        var result = XRayContextExtractor.xrayRootToOtelTraceId("");
        assertNull(result);
    }

    @Test
    void xrayRootToOtelTraceId_onlyPrefix() {
        var result = XRayContextExtractor.xrayRootToOtelTraceId("1-");
        assertNull(result);
    }

    @Test
    void xrayRootToOtelTraceId_noDashes() {
        // Missing second dash — produces wrong number of hex chars after stripping
        var result = XRayContextExtractor.xrayRootToOtelTraceId("1-5759e988bd862e3fe1be46a994272793");
        // After stripping "1-" and removing dashes, we get 32 chars — but format is unusual
        // This should still work because the conversion just strips "1-" and removes "-"
        assertEquals("5759e988bd862e3fe1be46a994272793", result);
    }

    // ─── Parsing full X-Ray header format (testing via the static method) ──

    @ParameterizedTest
    @ValueSource(
            strings = {
                "1-5759e988-bd862e3fe1be46a994272793",
                "1-aabbccdd-11223344556677889900aabb",
                "1-12345678-abcdefabcdefabcdefabcdef"
            })
    void xrayRootToOtelTraceId_variousValidRoots(String root) {
        var result = XRayContextExtractor.xrayRootToOtelTraceId(root);
        assertNotNull(result);
        assertEquals(32, result.length());
        assertTrue(result.matches("[0-9a-f]{32}"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "1-short", "Root=1-abc-def"})
    void xrayRootToOtelTraceId_variousInvalidInputs(String root) {
        if (root == null) {
            assertThrows(NullPointerException.class, () -> XRayContextExtractor.xrayRootToOtelTraceId(root));
        } else {
            var result = XRayContextExtractor.xrayRootToOtelTraceId(root);
            assertNull(result);
        }
    }
}
