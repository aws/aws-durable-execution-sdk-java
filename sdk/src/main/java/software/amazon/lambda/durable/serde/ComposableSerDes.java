// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;

/**
 * An immutable SerDes processing pipeline.
 *
 * <p>The first component is the value codec. Every later component is a {@link SerDesStage} that consumes and produces
 * a string. Serialization runs from first to last; deserialization runs from last to first. Each stage returns
 * unrecognized input unchanged, allowing raw values to pass through to the root value codec.
 */
public final class ComposableSerDes implements SerDes {
    private final SerDes valueCodec;
    private final List<SerDesStage> stages;

    private ComposableSerDes(SerDes valueCodec, List<SerDesStage> stages) {
        this.valueCodec = Objects.requireNonNull(valueCodec, "valueCodec cannot be null");
        this.stages = List.copyOf(stages);
    }

    /**
     * Creates a pipeline with a value codec followed by zero or more string stages.
     *
     * @param valueCodec the value codec
     * @param remaining reversible string stages
     * @return an immutable pipeline
     */
    public static ComposableSerDes of(SerDes valueCodec, SerDesStage... remaining) {
        Objects.requireNonNull(remaining, "remaining stages cannot be null");
        valueCodec = Objects.requireNonNull(valueCodec, "valueCodec cannot be null");
        var stages = new ArrayList<SerDesStage>();
        if (valueCodec instanceof ComposableSerDes composable) {
            valueCodec = composable.valueCodec;
            stages.addAll(composable.stages);
        }
        for (var stage : remaining) {
            stages.add(Objects.requireNonNull(stage, "pipeline stage cannot be null"));
        }
        return new ComposableSerDes(valueCodec, stages);
    }

    /**
     * Creates a pipeline builder.
     *
     * @param valueCodec the value codec which converts values to and from strings
     * @return a new builder
     */
    public static Builder builder(SerDes valueCodec) {
        return new Builder(valueCodec);
    }

    /** Returns the value codec at the start of this pipeline. */
    public SerDes getValueCodec() {
        return valueCodec;
    }

    /** Returns a new pipeline with the supplied string stage appended. */
    @Override
    public ComposableSerDes then(SerDesStage stage) {
        var combined = new ArrayList<>(stages);
        combined.add(Objects.requireNonNull(stage, "stage cannot be null"));
        return new ComposableSerDes(valueCodec, combined);
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        String current = invokeValueCodecSerialize(valueCodec, value);
        for (int index = 0; index < stages.size(); index++) {
            current = invokeStageSerialize(stages.get(index), current, index + 1);
        }
        return current;
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        if (data == null) {
            return null;
        }
        Objects.requireNonNull(typeToken, "typeToken cannot be null");
        String current = data;
        for (int index = stages.size() - 1; index >= 0; index--) {
            current = invokeStageDeserialize(stages.get(index), current, index + 1);
        }
        return invokeValueCodecDeserialize(valueCodec, current, typeToken);
    }

    private static String invokeStageSerialize(SerDesStage stage, String value, int index) {
        try {
            var result = stage.serialize(value);
            if (result == null) {
                throw new SerDesException("Stage returned null for a non-null value");
            }
            return result;
        } catch (Throwable failure) {
            throw stageFailure(index, stage, "serialize", failure);
        }
    }

    private static String invokeStageDeserialize(SerDesStage stage, String data, int index) {
        try {
            var result = stage.deserialize(data);
            if (result == null) {
                throw new SerDesException("Stage returned null for non-null input");
            }
            return result;
        } catch (Throwable failure) {
            throw stageFailure(index, stage, "deserialize", failure);
        }
    }

    private static String invokeValueCodecSerialize(SerDes valueCodec, Object value) {
        try {
            var result = valueCodec.serialize(value);
            if (result == null) {
                throw new SerDesException("Value codec returned null for a non-null value");
            }
            return result;
        } catch (Throwable failure) {
            throw stageFailure(0, valueCodec, "serialize", failure);
        }
    }

    private static <T> T invokeValueCodecDeserialize(SerDes valueCodec, String data, TypeToken<T> typeToken) {
        try {
            return valueCodec.deserialize(data, typeToken);
        } catch (Throwable failure) {
            throw stageFailure(0, valueCodec, "deserialize", failure);
        }
    }

    private static RuntimeException stageFailure(int index, Object stage, String action, Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        var message = String.format(
                "SerDes pipeline stage %d (%s) failed to %s",
                index, stage.getClass().getName(), action);
        if (failure instanceof RetryableSerDesException) {
            return new RetryableSerDesException(message, failure);
        }
        return new SerDesException(message, failure);
    }

    /** Builder for an immutable {@link ComposableSerDes}. */
    public static final class Builder {
        private SerDes valueCodec;
        private final List<SerDesStage> stages = new ArrayList<>();

        private Builder(SerDes valueCodec) {
            this.valueCodec = Objects.requireNonNull(valueCodec, "valueCodec cannot be null");
            if (valueCodec instanceof ComposableSerDes composable) {
                this.valueCodec = composable.valueCodec;
                stages.addAll(composable.stages);
            }
        }

        /** Appends a reversible string stage. */
        public Builder then(SerDesStage stage) {
            stages.add(Objects.requireNonNull(stage, "stage cannot be null"));
            return this;
        }

        /** Returns the immutable pipeline. */
        public ComposableSerDes build() {
            return new ComposableSerDes(valueCodec, stages);
        }
    }
}
