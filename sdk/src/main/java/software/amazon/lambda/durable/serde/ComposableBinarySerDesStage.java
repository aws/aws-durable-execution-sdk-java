// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;

/**
 * A string SerDes stage containing an ordered chain of binary transformations.
 *
 * <p>Serialization converts the input string with the starting codec, applies binary SerDes instances in declaration
 * order, and converts the final bytes to a string with the ending codec. Deserialization reverses the complete process.
 */
public final class ComposableBinarySerDesStage implements SerDesStage {
    private final StringBinaryCodec startingCodec;
    private final List<BinarySerDes> binarySerDes;
    private final StringBinaryCodec endingCodec;

    private ComposableBinarySerDesStage(
            StringBinaryCodec startingCodec, List<BinarySerDes> binarySerDes, StringBinaryCodec endingCodec) {
        this.startingCodec = startingCodec;
        this.binarySerDes = List.copyOf(binarySerDes);
        this.endingCodec = endingCodec;
    }

    /** Creates a builder whose methods follow forward serialization order. */
    public static StartBuilder builder() {
        return new Builder();
    }

    @Override
    public String serialize(String value) {
        Objects.requireNonNull(value, "value cannot be null");
        var current = invokeToBytes(startingCodec, value, "starting codec");
        for (int index = 0; index < binarySerDes.size(); index++) {
            current = invokeSerialize(binarySerDes.get(index), current, index);
        }
        return invokeFromBytes(endingCodec, current, "ending codec");
    }

    @Override
    public String deserialize(String data) {
        Objects.requireNonNull(data, "data cannot be null");
        var current = invokeToBytes(endingCodec, data, "ending codec");
        for (int index = binarySerDes.size() - 1; index >= 0; index--) {
            current = invokeDeserialize(binarySerDes.get(index), current, index);
        }
        return invokeFromBytes(startingCodec, current, "starting codec");
    }

    private static byte[] invokeToBytes(StringBinaryCodec codec, String value, String name) {
        try {
            return requireResult(codec.toBytes(value), name);
        } catch (Throwable failure) {
            throw componentFailure(name, "convert string to bytes", failure);
        }
    }

    private static String invokeFromBytes(StringBinaryCodec codec, byte[] data, String name) {
        try {
            return requireResult(codec.fromBytes(data), name);
        } catch (Throwable failure) {
            throw componentFailure(name, "convert bytes to string", failure);
        }
    }

    private static byte[] invokeSerialize(BinarySerDes serDes, byte[] value, int index) {
        try {
            return requireResult(serDes.serialize(value), binaryStageName(index, serDes));
        } catch (Throwable failure) {
            throw componentFailure(binaryStageName(index, serDes), "serialize", failure);
        }
    }

    private static byte[] invokeDeserialize(BinarySerDes serDes, byte[] data, int index) {
        try {
            return requireResult(serDes.deserialize(data), binaryStageName(index, serDes));
        } catch (Throwable failure) {
            throw componentFailure(binaryStageName(index, serDes), "deserialize", failure);
        }
    }

    private static <T> T requireResult(T result, String component) {
        if (result == null) {
            throw new SerDesException(component + " returned null for non-null input");
        }
        return result;
    }

    private static String binaryStageName(int index, BinarySerDes serDes) {
        return String.format("binary stage %d (%s)", index, serDes.getClass().getName());
    }

    private static RuntimeException componentFailure(String component, String action, Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        var message = String.format("Composable binary SerDes %s failed to %s", component, action);
        if (failure instanceof RetryableSerDesException) {
            return new RetryableSerDesException(message, failure);
        }
        return new SerDesException(message, failure);
    }

    /** Builder stage that requires the starting string/binary codec. */
    public interface StartBuilder {
        /**
         * Sets the codec that converts the input string to bytes during serialization.
         *
         * @param codec the starting boundary codec
         * @return the binary-stage builder
         */
        BinaryStagesBuilder startWith(StringBinaryCodec codec);
    }

    /** Builder stage that accepts binary SerDes instances in processing order. */
    public interface BinaryStagesBuilder {
        /**
         * Appends a binary transformation.
         *
         * @param serDes the binary SerDes
         * @return this builder stage
         */
        BinaryStagesBuilder then(BinarySerDes serDes);

        /**
         * Sets the codec that converts the final bytes to a string during serialization.
         *
         * @param codec the ending boundary codec
         * @return the completed builder
         */
        CompletedBuilder endWith(StringBinaryCodec codec);
    }

    /** Builder stage that permits only construction of the completed binary pipeline. */
    public interface CompletedBuilder {
        /** Returns the immutable string stage. */
        ComposableBinarySerDesStage build();
    }

    private static final class Builder implements StartBuilder, BinaryStagesBuilder, CompletedBuilder {
        private StringBinaryCodec startingCodec;
        private final List<BinarySerDes> binarySerDes = new ArrayList<>();
        private StringBinaryCodec endingCodec;

        @Override
        public BinaryStagesBuilder startWith(StringBinaryCodec codec) {
            startingCodec = Objects.requireNonNull(codec, "starting codec cannot be null");
            return this;
        }

        @Override
        public BinaryStagesBuilder then(BinarySerDes serDes) {
            binarySerDes.add(Objects.requireNonNull(serDes, "binary SerDes cannot be null"));
            return this;
        }

        @Override
        public CompletedBuilder endWith(StringBinaryCodec codec) {
            endingCodec = Objects.requireNonNull(codec, "ending codec cannot be null");
            return this;
        }

        @Override
        public ComposableBinarySerDesStage build() {
            return new ComposableBinarySerDesStage(startingCodec, binarySerDes, endingCodec);
        }
    }
}
