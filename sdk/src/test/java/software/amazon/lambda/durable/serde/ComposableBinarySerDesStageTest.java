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
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;

class ComposableBinarySerDesStageTest {

    @Test
    void processesBoundariesAndBinarySerDesInDeclarationOrder() {
        var calls = new ArrayList<String>();
        var stage = ComposableBinarySerDesStage.builder()
                .startWith(recordingCodec("starting", calls))
                .then(appendingSerDes("first", (byte) 1, calls))
                .then(appendingSerDes("second", (byte) 2, calls))
                .endWith(recordingCodec("ending", calls))
                .build();

        var serialized = stage.serialize("value");
        var deserialized = stage.deserialize(serialized);

        assertEquals(Base64.getEncoder().encodeToString(new byte[] {'v', 'a', 'l', 'u', 'e', 1, 2}), serialized);
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
    void composesWithRootSerDesAsOneStringStage() {
        var stage = ComposableBinarySerDesStage.builder()
                .startWith(Utf8StringBinaryCodec.INSTANCE)
                .then(xorSerDes((byte) 0x5A))
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

        var serialized = stage.serialize("value");

        assertEquals(Base64.getEncoder().encodeToString("eulav".getBytes(StandardCharsets.UTF_8)), serialized);
        assertEquals("value", stage.deserialize(serialized));
    }

    @Test
    void delegatesDurableContextRequirement() {
        var contextCodec = new StringBinaryCodec() {
            @Override
            public byte[] toBytes(String value) {
                return value.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String fromBytes(byte[] data) {
                return new String(data, StandardCharsets.UTF_8);
            }

            @Override
            public boolean requiresDurableContext() {
                return true;
            }
        };

        var stage = ComposableBinarySerDesStage.builder()
                .startWith(contextCodec)
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();

        assertTrue(stage.requiresDurableContext());
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
                .then(new BinarySerDes() {
                    @Override
                    public byte[] serialize(byte[] value) {
                        return null;
                    }

                    @Override
                    public byte[] deserialize(byte[] data) {
                        return data;
                    }
                })
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();

        var failure = assertThrows(SerDesException.class, () -> nullStage.serialize("value"));
        assertTrue(failure.getMessage().contains("binary stage 0"));
    }

    @Test
    void preservesRetryableFailuresAndFatalErrors() {
        var retryableStage = ComposableBinarySerDesStage.builder()
                .startWith(Utf8StringBinaryCodec.INSTANCE)
                .then(new BinarySerDes() {
                    @Override
                    public byte[] serialize(byte[] value) {
                        throw new RetryableSerDesException("retry");
                    }

                    @Override
                    public byte[] deserialize(byte[] data) {
                        return data;
                    }
                })
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();

        var retryable = assertThrows(RetryableSerDesException.class, () -> retryableStage.serialize("value"));
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

        assertSame(fatalError, assertThrows(AssertionError.class, () -> fatalStage.serialize("value")));
    }

    @Test
    void providedCodecsRoundTrip() {
        var value = "hello λ";
        var bytes = Utf8StringBinaryCodec.INSTANCE.toBytes(value);

        assertEquals(value, Utf8StringBinaryCodec.INSTANCE.fromBytes(bytes));
        assertArrayEquals(
                bytes, Base64StringBinaryCodec.INSTANCE.toBytes(Base64StringBinaryCodec.INSTANCE.fromBytes(bytes)));
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

    private static BinarySerDes appendingSerDes(String name, byte suffix, List<String> calls) {
        return new BinarySerDes() {
            @Override
            public byte[] serialize(byte[] value) {
                calls.add(name + "-serialize");
                var result = Arrays.copyOf(value, value.length + 1);
                result[value.length] = suffix;
                return result;
            }

            @Override
            public byte[] deserialize(byte[] data) {
                calls.add(name + "-deserialize");
                if (data.length == 0 || data[data.length - 1] != suffix) {
                    throw new SerDesException("Unexpected suffix");
                }
                return Arrays.copyOf(data, data.length - 1);
            }
        };
    }

    private static BinarySerDes xorSerDes(byte key) {
        return new BinarySerDes() {
            @Override
            public byte[] serialize(byte[] value) {
                return xor(value, key);
            }

            @Override
            public byte[] deserialize(byte[] data) {
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
