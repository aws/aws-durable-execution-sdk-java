// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;

class XRayContextExtractorTest {

    @Test
    void extract_withoutEnvVar_returnsRoot() {
        // _X_AMZN_TRACE_ID is not set in test environment
        var extractor = new XRayContextExtractor();
        var context = extractor.extract();

        // Should return root context when env var is missing
        assertEquals(Context.root(), context);
    }

    @Test
    void extract_implementsContextExtractor() {
        // Verify XRayContextExtractor implements the ContextExtractor interface
        ContextExtractor extractor = new XRayContextExtractor();
        assertNotNull(extractor.extract());
    }
}
