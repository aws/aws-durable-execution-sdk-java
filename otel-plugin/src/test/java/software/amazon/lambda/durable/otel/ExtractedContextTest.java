// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Tests {@link ExtractedContext} construction, focusing on null-sampling normalization and legacy deserialization. */
class ExtractedContextTest {

    private static final String TRACE_ID = "aabbccddee112233445566778899aabb";
    private static final String PARENT_SPAN_ID = "53995c3f42cd8ad8";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void canonicalConstructor_normalizesNullSamplingToUndecided() {
        // The canonical constructor is public; a null sampling must be normalized so downstream resolution, which
        // switches on the decision, does not throw and silently disable telemetry.
        var context = new ExtractedContext(TRACE_ID, PARENT_SPAN_ID, null);
        assertEquals(ExtractedContext.Sampling.UNDECIDED, context.sampling());
    }

    @Test
    void twoArgConstructor_defaultsToUndecided() {
        var context = new ExtractedContext(TRACE_ID, PARENT_SPAN_ID);
        assertEquals(ExtractedContext.Sampling.UNDECIDED, context.sampling());
    }

    @Test
    void deserialize_legacyValueWithoutSamplingField_isUndecided() throws Exception {
        // A value serialized before the sampling component existed omits the field; Jackson leaves it null. The
        // compact constructor must normalize it to UNDECIDED rather than producing a context that throws on use.
        var legacyJson = "{\"traceId\":\"" + TRACE_ID + "\",\"parentSpanId\":\"" + PARENT_SPAN_ID + "\"}";

        var context = mapper.readValue(legacyJson, ExtractedContext.class);

        assertEquals(TRACE_ID, context.traceId());
        assertEquals(PARENT_SPAN_ID, context.parentSpanId());
        assertEquals(ExtractedContext.Sampling.UNDECIDED, context.sampling());
    }

    @Test
    void deserialize_explicitNullSampling_isUndecided() throws Exception {
        var json = "{\"traceId\":\"" + TRACE_ID + "\",\"parentSpanId\":null,\"sampling\":null}";

        var context = mapper.readValue(json, ExtractedContext.class);

        assertEquals(ExtractedContext.Sampling.UNDECIDED, context.sampling());
    }

    @Test
    void serialize_thenDeserialize_roundTripsSampling() throws Exception {
        var original = new ExtractedContext(TRACE_ID, PARENT_SPAN_ID, ExtractedContext.Sampling.SAMPLED);

        var roundTripped = mapper.readValue(mapper.writeValueAsString(original), ExtractedContext.class);

        assertEquals(original, roundTripped);
        assertEquals(ExtractedContext.Sampling.SAMPLED, roundTripped.sampling());
    }
}
