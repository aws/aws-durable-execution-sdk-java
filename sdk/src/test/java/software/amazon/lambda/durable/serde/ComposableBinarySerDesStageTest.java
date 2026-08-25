// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;

class ComposableBinarySerDesStageTest {
    private static final String BINARY_FRAME_MARKER = "__durable_execution_composable_binary_serdes:";
    private static final String BINARY_FRAME_PREFIX = BINARY_FRAME_MARKER + "1:";
    private static final SerDesContext CONTEXT =
            SerDesContext.forExecution("arn", "invocation", "execution", SerDesPayloadKind.RESULT);

    @Test
    void processesBoundariesAndBinaryStagesInDeclarationOrder() {
        var calls = new ArrayList<String>();
        var stage = ComposableBinarySerDesStage.builder()
                .startWith(recordingCodec("starting", calls))
                .then(appendingStage("first", (byte) 1, calls))
                .then(appendingStage("second", (byte) 2, calls))
                .endWith(recordingCodec("ending", calls))
                .build();

        var serialized = stage.serialize("value", CONTEXT);
        var deserialized = stage.deserialize(serialized, CONTEXT);

        assertEquals(
                BINARY_FRAME_PREFIX + Base64.getEncoder().encodeToString(new byte[] {'v', 'a', 'l', 'u', 'e', 1, 2}),
                serialized);
        assertEquals("value", deserialized);
        assertEquals(
                List.of(
                        "starting-to-bytes",
                        "first-serialize",
                        "second-serialize",
                        "ending-from-bytes",
                        "ending-to-bytes",
                        "second-deserialize",
                        "first-deserialize",
                        "starting-from-bytes"),
                calls);
    }

    @Test
    void passesTheSameContextToEveryBinaryStageCall() {
        var serializeContext = new AtomicReference<SerDesContext>();
        var deserializeContext = new AtomicReference<SerDesContext>();
        var stage = ComposableBinarySerDesStage.builder()
                .startWith(Utf8StringBinaryCodec.INSTANCE)
                .then(new BinarySerDesStage() {
                    @Override
                    public byte[] serialize(byte[] value, SerDesContext context) {
                        serializeContext.set(context);
                        return value;
                    }

                    @Override
                    public byte[] deserialize(byte[] data, SerDesContext context) {
                        deserializeContext.set(context);
                        return data;
                    }
                })
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();

        var serialized = stage.serialize("value", CONTEXT);
        stage.deserialize(serialized, CONTEXT);

        assertSame(CONTEXT, serializeContext.get());
        assertSame(CONTEXT, deserializeContext.get());
    }

    @Test
    void receivesTheOriginalValueFromTheRootPipeline() {
        var serializeContext = new AtomicReference<SerDesContext>();
        var binaryStage = ComposableBinarySerDesStage.builder()
                .startWith(Utf8StringBinaryCodec.INSTANCE)
                .then(new BinarySerDesStage() {
                    @Override
                    public byte[] serialize(byte[] value, SerDesContext context) {
                        serializeContext.set(context);
                        return value;
                    }

                    @Override
                    public byte[] deserialize(byte[] data, SerDesContext context) {
                        return data;
                    }
                })
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();
        var pipeline = new JacksonSerDes().then(binaryStage);
        var originalValue = Map.of("id", 42);

        new SerDesRunner(null).serialize(pipeline, originalValue, CONTEXT);

        assertSame(originalValue, serializeContext.get().originalValue());
    }

    @Test
    void composesWithRootSerDesAsOneStringStage() {
        var stage = ComposableBinarySerDesStage.builder()
                .startWith(Utf8StringBinaryCodec.INSTANCE)
                .then(xorStage((byte) 0x5A))
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();
        var pipeline = new JacksonSerDes().then(stage);

        var serialized = pipeline.serialize("value");

        assertEquals("value", pipeline.deserialize(serialized, TypeToken.get(String.class)));
    }

    @Test
    void supportsCustomCodecsAtBothBoundaries() {
        var reverseUtf8 = new StringBinaryCodec() {
            @Override
            public byte[] toBytes(String value) {
                var bytes = value.getBytes(StandardCharsets.UTF_8);
                reverse(bytes);
                return bytes;
            }

            @Override
            public String fromBytes(byte[] data) {
                var copy = Arrays.copyOf(data, data.length);
                reverse(copy);
                return new String(copy, StandardCharsets.UTF_8);
            }
        };
        var stage = ComposableBinarySerDesStage.builder()
                .startWith(reverseUtf8)
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();

        var serialized = stage.serialize("value", CONTEXT);

        assertEquals(
                BINARY_FRAME_PREFIX + Base64.getEncoder().encodeToString("eulav".getBytes(StandardCharsets.UTF_8)),
                serialized);
        assertEquals("value", stage.deserialize(serialized, CONTEXT));
    }

    @Test
    void passesThroughUnrecognizedInputAndRejectsInvalidFrames() {
        var stage = ComposableBinarySerDesStage.builder()
                .startWith(Utf8StringBinaryCodec.INSTANCE)
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();

        assertEquals("\"external\"", stage.deserialize("\"external\"", CONTEXT));
        assertThrows(SerDesException.class, () -> stage.deserialize(BINARY_FRAME_MARKER + "2:value", CONTEXT));
        assertThrows(SerDesException.class, () -> stage.deserialize(BINARY_FRAME_PREFIX + "not-base64!", CONTEXT));
    }

    @Test
    void validatesConfigurationAndComponentResults() {
        assertThrows(NullPointerException.class, () -> ComposableBinarySerDesStage.builder()
                .startWith(null));
        assertThrows(NullPointerException.class, () -> ComposableBinarySerDesStage.builder()
                .startWith(Utf8StringBinaryCodec.INSTANCE)
                .then(null));
        assertThrows(NullPointerException.class, () -> ComposableBinarySerDesStage.builder()
                .startWith(Utf8StringBinaryCodec.INSTANCE)
                .endWith(null));

        var nullStage = ComposableBinarySerDesStage.builder()
                .startWith(Utf8StringBinaryCodec.INSTANCE)
                .then(new BinarySerDesStage() {
                    @Override
                    public byte[] serialize(byte[] value, SerDesContext context) {
                        return null;
                    }

                    @Override
                    public byte[] deserialize(byte[] data, SerDesContext context) {
                        return data;
                    }
                })
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();

        var failure = assertThrows(SerDesException.class, () -> nullStage.serialize("value", CONTEXT));
        assertTrue(failure.getMessage().contains("binary stage 0"));
    }

    @Test
    void preservesRetryableFailuresAndFatalErrors() {
        var retryableStage = ComposableBinarySerDesStage.builder()
                .startWith(Utf8StringBinaryCodec.INSTANCE)
                .then(new BinarySerDesStage() {
                    @Override
                    public byte[] serialize(byte[] value, SerDesContext context) {
                        throw new RetryableSerDesException("retry");
                    }

                    @Override
                    public byte[] deserialize(byte[] data, SerDesContext context) {
                        return data;
                    }
                })
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();

        var retryable = assertThrows(RetryableSerDesException.class, () -> retryableStage.serialize("value", CONTEXT));
        assertTrue(retryable.getMessage().contains("binary stage 0"));

        var fatalError = new AssertionError("fatal");
        var fatalStage = ComposableBinarySerDesStage.builder()
                .startWith(new StringBinaryCodec() {
                    @Override
                    public byte[] toBytes(String value) {
                        throw fatalError;
                    }

                    @Override
                    public String fromBytes(byte[] data) {
                        return "";
                    }
                })
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();

        assertSame(fatalError, assertThrows(AssertionError.class, () -> fatalStage.serialize("value", CONTEXT)));
    }

    @Test
    void providedCodecsRoundTrip() {
        var value = "hello λ";
        var bytes = Utf8StringBinaryCodec.INSTANCE.toBytes(value);

        assertEquals(value, Utf8StringBinaryCodec.INSTANCE.fromBytes(bytes));
        assertArrayEquals(
                bytes, Base64StringBinaryCodec.INSTANCE.toBytes(Base64StringBinaryCodec.INSTANCE.fromBytes(bytes)));
    }

    @Test
    void utf8CodecRejectsLossyConversions() {
        assertThrows(SerDesException.class, () -> Utf8StringBinaryCodec.INSTANCE.toBytes("lone surrogate \uD800"));
        assertThrows(
                SerDesException.class,
                () -> Utf8StringBinaryCodec.INSTANCE.fromBytes(new byte[] {(byte) 0xC3, (byte) 0x28}));
    }

    private static StringBinaryCodec recordingCodec(String name, List<String> calls) {
        return new StringBinaryCodec() {
            @Override
            public byte[] toBytes(String value) {
                calls.add(name + "-to-bytes");
                return name.equals("ending")
                        ? Base64.getDecoder().decode(value)
                        : value.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String fromBytes(byte[] data) {
                calls.add(name + "-from-bytes");
                return name.equals("ending")
                        ? Base64.getEncoder().encodeToString(data)
                        : new String(data, StandardCharsets.UTF_8);
            }
        };
    }

    private static BinarySerDesStage appendingStage(String name, byte suffix, List<String> calls) {
        return new BinarySerDesStage() {
            @Override
            public byte[] serialize(byte[] value, SerDesContext context) {
                calls.add(name + "-serialize");
                var result = Arrays.copyOf(value, value.length + 1);
                result[value.length] = suffix;
                return result;
            }

            @Override
            public byte[] deserialize(byte[] data, SerDesContext context) {
                calls.add(name + "-deserialize");
                if (data.length == 0 || data[data.length - 1] != suffix) {
                    throw new SerDesException("Unexpected suffix");
                }
                return Arrays.copyOf(data, data.length - 1);
            }
        };
    }

    private static BinarySerDesStage xorStage(byte key) {
        return new BinarySerDesStage() {
            @Override
            public byte[] serialize(byte[] value, SerDesContext context) {
                return xor(value, key);
            }

            @Override
            public byte[] deserialize(byte[] data, SerDesContext context) {
                return xor(data, key);
            }
        };
    }

    private static byte[] xor(byte[] value, byte key) {
        var result = Arrays.copyOf(value, value.length);
        for (int index = 0; index < result.length; index++) {
            result[index] ^= key;
        }
        return result;
    }

    private static void reverse(byte[] value) {
        for (int left = 0, right = value.length - 1; left < right; left++, right--) {
            var temporary = value[left];
            value[left] = value[right];
            value[right] = temporary;
        }
    }
}
