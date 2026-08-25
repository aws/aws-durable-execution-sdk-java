// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;

/**
 * An immutable SerDes processing pipeline.
 *
 * <p>The first stage is the value codec. Every later stage consumes and produces a string. Serialization runs from
 * first to last; deserialization runs from last to first.
 */
public final class ComposableSerDes implements SerDes {
    private final SerDes valueCodec;
    private final List<Object> stages;

    private ComposableSerDes(SerDes valueCodec, List<Object> stages) {
        this.valueCodec = Objects.requireNonNull(valueCodec, "valueCodec cannot be null");
        if (!stages.isEmpty() && valueCodec.isTerminalPipelineStage()) {
            throw terminalStageFailure(0, valueCodec);
        }
        for (int index = 0; index < stages.size() - 1; index++) {
            if (isTerminal(stages.get(index))) {
                throw terminalStageFailure(index + 1, stages.get(index));
            }
        }
        this.stages = List.copyOf(stages);
    }

    /**
     * Creates a pipeline with a value codec followed by zero or more string stages.
     *
     * @param first the value codec
     * @param remaining reversible SerDes stages
     * @return an immutable pipeline
     */
    public static ComposableSerDes of(SerDes first, SerDes... remaining) {
        Objects.requireNonNull(remaining, "remaining stages cannot be null");
        var valueCodec = Objects.requireNonNull(first, "first stage cannot be null");
        var stages = new ArrayList<Object>();
        if (valueCodec instanceof ComposableSerDes composable) {
            valueCodec = composable.valueCodec;
            stages.addAll(composable.stages);
        }
        Arrays.stream(remaining)
                .map(stage -> Objects.requireNonNull(stage, "pipeline stage cannot be null"))
                .forEach(stage -> addFlattened(stages, stage));
        return new ComposableSerDes(valueCodec, stages);
    }

    /**
     * Creates a pipeline builder.
     *
     * @param valueCodec the first stage which converts values to and from strings
     * @return a new builder
     */
    public static Builder builder(SerDes valueCodec) {
        return new Builder(valueCodec);
    }

    /** Returns the value codec at the start of this pipeline. */
    public SerDes getValueCodec() {
        return valueCodec;
    }

    @Override
    public boolean requiresDurableContext() {
        return valueCodec.requiresDurableContext() || stages.stream().anyMatch(ComposableSerDes::requiresContext);
    }

    @Override
    public boolean isTerminalPipelineStage() {
        return stages.isEmpty() ? valueCodec.isTerminalPipelineStage() : isTerminal(stages.get(stages.size() - 1));
    }

    /** Returns a new pipeline with the supplied stage appended. */
    @Override
    public ComposableSerDes then(SerDes stage) {
        var combined = new ArrayList<>(stages);
        addFlattened(combined, Objects.requireNonNull(stage, "stage cannot be null"));
        return new ComposableSerDes(valueCodec, combined);
    }

    /** Returns a new pipeline with the supplied string stage appended. */
    @Override
    public ComposableSerDes then(SerDesStage stage) {
        var combined = new ArrayList<>(stages);
        addFlattened(combined, Objects.requireNonNull(stage, "stage cannot be null"));
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
            var decoded = invokeStageDeserialize(stages.get(index), current, index + 1);
            current = decoded.value();
            if (decoded.skipRemainingStages()) {
                break;
            }
        }
        return invokeValueCodecDeserialize(valueCodec, current, typeToken);
    }

    private static String invokeStageSerialize(Object stage, String value, int index) {
        try {
            var result =
                    stage instanceof SerDes serDes ? serDes.serialize(value) : ((SerDesStage) stage).serialize(value);
            if (result == null) {
                throw new SerDesException("Stage returned null for a non-null value");
            }
            return result;
        } catch (Throwable failure) {
            throw stageFailure(index, stage, "serialize", failure);
        }
    }

    private static SerDesStageResult invokeStageDeserialize(Object stage, String data, int index) {
        try {
            SerDesStageResult result;
            if (stage instanceof SerDes serDes) {
                result = serDes.deserializePipelineStage(data);
            } else {
                result = ((SerDesStage) stage).deserializePipelineStage(data);
            }
            if (result == null) {
                throw new SerDesException("Stage returned a null pipeline result");
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

    private static IllegalArgumentException terminalStageFailure(int index, Object stage) {
        return new IllegalArgumentException(String.format(
                "SerDes pipeline stage %d (%s) must be the final stage",
                index, stage.getClass().getName()));
    }

    private static boolean requiresContext(Object stage) {
        return stage instanceof SerDes serDes
                ? serDes.requiresDurableContext()
                : ((SerDesStage) stage).requiresDurableContext();
    }

    private static boolean isTerminal(Object stage) {
        return stage instanceof SerDes serDes
                ? serDes.isTerminalPipelineStage()
                : ((SerDesStage) stage).isTerminalPipelineStage();
    }

    private static void addFlattened(List<Object> target, SerDes stage) {
        if (stage instanceof ComposableSerDes composable) {
            target.add(composable.valueCodec);
            target.addAll(composable.stages);
        } else {
            target.add(stage);
        }
    }

    private static void addFlattened(List<Object> target, SerDesStage stage) {
        target.add(stage);
    }

    /** Builder for an immutable {@link ComposableSerDes}. */
    public static final class Builder {
        private SerDes valueCodec;
        private final List<Object> stages = new ArrayList<>();

        private Builder(SerDes valueCodec) {
            this.valueCodec = Objects.requireNonNull(valueCodec, "valueCodec cannot be null");
            if (valueCodec instanceof ComposableSerDes composable) {
                this.valueCodec = composable.valueCodec;
                stages.addAll(composable.stages);
            }
        }

        /** Appends a reversible SerDes stage. */
        public Builder then(SerDes stage) {
            addFlattened(stages, Objects.requireNonNull(stage, "stage cannot be null"));
            return this;
        }

        /** Appends a reversible string stage. */
        public Builder then(SerDesStage stage) {
            addFlattened(stages, Objects.requireNonNull(stage, "stage cannot be null"));
            return this;
        }

        /** Returns the immutable pipeline. */
        public ComposableSerDes build() {
            return new ComposableSerDes(valueCodec, stages);
        }
    }
}
