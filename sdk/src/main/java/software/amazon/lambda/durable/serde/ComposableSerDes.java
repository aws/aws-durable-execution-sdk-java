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
 * <p>The first stage is the value codec. Every later stage must be a reversible string transformation. Serialization
 * runs from first to last; deserialization runs from last to first.
 */
public final class ComposableSerDes implements SerDes {
    private final List<SerDes> stages;

    private ComposableSerDes(List<SerDes> stages) {
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("ComposableSerDes requires at least one stage");
        }
        for (int index = 0; index < stages.size() - 1; index++) {
            if (stages.get(index).isTerminalPipelineStage()) {
                throw new IllegalArgumentException(String.format(
                        "SerDes pipeline stage %d (%s) must be the final stage",
                        index, stages.get(index).getClass().getName()));
            }
        }
        this.stages = List.copyOf(stages);
    }

    /**
     * Creates a pipeline with a value codec followed by zero or more string stages.
     *
     * @param first the value codec
     * @param remaining reversible string stages
     * @return an immutable pipeline
     */
    public static ComposableSerDes of(SerDes first, SerDes... remaining) {
        Objects.requireNonNull(remaining, "remaining stages cannot be null");
        var stages = new ArrayList<SerDes>();
        addFlattened(stages, Objects.requireNonNull(first, "first stage cannot be null"));
        Arrays.stream(remaining)
                .map(stage -> Objects.requireNonNull(stage, "pipeline stage cannot be null"))
                .forEach(stage -> addFlattened(stages, stage));
        return new ComposableSerDes(stages);
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

    /**
     * Returns the value codec at the start of this pipeline.
     *
     * @return the first pipeline stage
     */
    public SerDes getValueCodec() {
        return stages.get(0);
    }

    @Override
    public boolean requiresDurableContext() {
        return stages.stream().anyMatch(SerDes::requiresDurableContext);
    }

    @Override
    public boolean isTerminalPipelineStage() {
        return stages.get(stages.size() - 1).isTerminalPipelineStage();
    }

    /**
     * Returns a new pipeline with the supplied stage appended.
     *
     * @param stage the reversible string stage to append
     * @return a new immutable pipeline
     */
    @Override
    public ComposableSerDes then(SerDes stage) {
        var combined = new ArrayList<>(stages);
        addFlattened(combined, Objects.requireNonNull(stage, "stage cannot be null"));
        return new ComposableSerDes(combined);
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        String current = invokeSerialize(stages.get(0), value, 0);
        for (int index = 1; index < stages.size(); index++) {
            current = invokeSerialize(stages.get(index), current, index);
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
        for (int index = stages.size() - 1; index > 0; index--) {
            var decoded = invokeStringStageDeserialize(stages.get(index), current, index);
            current = decoded.value();
            if (decoded.skipRemainingStages()) {
                break;
            }
        }
        return invokeDeserialize(stages.get(0), current, typeToken, 0);
    }

    private static SerDesStageResult invokeStringStageDeserialize(SerDes stage, String data, int index) {
        try {
            var result = stage.deserializePipelineStage(data);
            if (result == null) {
                throw new SerDesException("Stage returned a null pipeline result");
            }
            return result;
        } catch (Throwable failure) {
            throw stageFailure(index, stage, "deserialize", failure);
        }
    }

    private static String invokeSerialize(SerDes stage, Object value, int index) {
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

    private static <T> T invokeDeserialize(SerDes stage, String data, TypeToken<T> typeToken, int index) {
        try {
            return stage.deserialize(data, typeToken);
        } catch (Throwable failure) {
            throw stageFailure(index, stage, "deserialize", failure);
        }
    }

    private static RuntimeException stageFailure(int index, SerDes stage, String action, Throwable failure) {
        var message = String.format(
                "SerDes pipeline stage %d (%s) failed to %s",
                index, stage.getClass().getName(), action);
        if (failure instanceof RetryableSerDesException) {
            return new RetryableSerDesException(message, failure);
        }
        return new SerDesException(message, failure);
    }

    private static void addFlattened(List<SerDes> target, SerDes stage) {
        if (stage instanceof ComposableSerDes composable) {
            target.addAll(composable.stages);
        } else {
            target.add(stage);
        }
    }

    /** Builder for an immutable {@link ComposableSerDes}. */
    public static final class Builder {
        private final List<SerDes> stages = new ArrayList<>();

        private Builder(SerDes valueCodec) {
            addFlattened(stages, Objects.requireNonNull(valueCodec, "valueCodec cannot be null"));
        }

        /**
         * Appends a reversible string stage.
         *
         * @param stage the stage to append
         * @return this builder
         */
        public Builder then(SerDes stage) {
            addFlattened(stages, Objects.requireNonNull(stage, "stage cannot be null"));
            return this;
        }

        /** Returns the immutable pipeline. */
        public ComposableSerDes build() {
            return new ComposableSerDes(stages);
        }
    }
}
