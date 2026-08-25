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
 * <p>The value codec may produce any representation type. The {@link SerDesStage} chain starts with that representation
 * and may exchange arbitrary intermediate Java types. Serialization runs from first to last; deserialization runs from
 * last to first. Only the final serialized value must be a string.
 *
 * @param <R> the representation exchanged between the value codec and the first stage
 */
public final class ComposableSerDes<R> implements SerDes {
    private final ValueSerDes<R> valueCodec;
    private final List<SerDesStage<?, ?>> stages;

    private ComposableSerDes(ValueSerDes<R> valueCodec, List<SerDesStage<?, ?>> stages) {
        this.valueCodec = Objects.requireNonNull(valueCodec, "valueCodec cannot be null");
        if (!stages.isEmpty() && valueCodec.isTerminalPipelineStage()) {
            throw terminalStageFailure(0, valueCodec);
        }
        for (int index = 0; index < stages.size() - 1; index++) {
            if (stages.get(index).isTerminalPipelineStage()) {
                throw terminalStageFailure(index + 1, stages.get(index));
            }
        }
        this.stages = List.copyOf(stages);
    }

    /**
     * Creates a pipeline containing only a value codec.
     *
     * @param valueCodec the value codec
     * @return an immutable pipeline
     */
    public static ComposableSerDes<String> of(SerDes valueCodec) {
        return new ComposableSerDes<>(valueCodec, List.of());
    }

    /**
     * Creates a pipeline with a typed value codec followed by a stage or stage chain that produces the checkpoint
     * string.
     *
     * @param valueCodec the value codec
     * @param stage the reversible typed stage or stage chain
     * @param <R> the representation exchanged between the value codec and the first stage
     * @return an immutable pipeline
     */
    public static <R> ComposableSerDes<R> of(ValueSerDes<R> valueCodec, SerDesStage<? super R, String> stage) {
        var stages = new ArrayList<SerDesStage<?, ?>>();
        addFlattened(stages, Objects.requireNonNull(stage, "stage cannot be null"));
        return new ComposableSerDes<>(valueCodec, stages);
    }

    /** Returns the value codec at the start of this pipeline. */
    public ValueSerDes<R> getValueCodec() {
        return valueCodec;
    }

    @Override
    public boolean requiresDurableContext() {
        return valueCodec.requiresDurableContext() || stages.stream().anyMatch(SerDesStage::requiresDurableContext);
    }

    @Override
    public boolean isTerminalPipelineStage() {
        return stages.isEmpty()
                ? valueCodec.isTerminalPipelineStage()
                : stages.get(stages.size() - 1).isTerminalPipelineStage();
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        Object current = invokeValueCodecSerialize(valueCodec, value);
        for (int index = 0; index < stages.size(); index++) {
            current = invokeStageSerialize(stages.get(index), current, index + 1);
        }
        if (!(current instanceof String serialized)) {
            throw new SerDesException(
                    "SerDes pipeline final stage returned " + current.getClass().getName() + " instead of String");
        }
        return serialized;
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        if (data == null) {
            return null;
        }
        Objects.requireNonNull(typeToken, "typeToken cannot be null");
        Object current = data;
        boolean skipToExternalCodec = false;
        for (int index = stages.size() - 1; index >= 0; index--) {
            var decoded = invokeStageDeserialize(stages.get(index), current, index + 1);
            current = decoded.value();
            if (decoded.skipRemainingStages()) {
                skipToExternalCodec = true;
                break;
            }
        }
        if (skipToExternalCodec) {
            if (!(current instanceof String externalInput)) {
                throw new SerDesException("SerDes pipeline external boundary produced "
                        + current.getClass().getName()
                        + " instead of String");
            }
            return invokeExternalValueCodecDeserialize(valueCodec, externalInput, typeToken);
        }
        return invokeValueCodecDeserialize(valueCodec, current, typeToken);
    }

    @SuppressWarnings("unchecked")
    private static Object invokeStageSerialize(SerDesStage<?, ?> stage, Object value, int index) {
        try {
            var result = ((SerDesStage<Object, Object>) stage).serialize(value);
            if (result == null) {
                throw new SerDesException("Stage returned null for a non-null value");
            }
            return result;
        } catch (Throwable failure) {
            throw stageFailure(index, stage, "serialize", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static SerDesStageResult invokeStageDeserialize(SerDesStage<?, ?> stage, Object data, int index) {
        try {
            var result = ((SerDesStage<Object, Object>) stage).deserializePipelineStage(data);
            if (result == null) {
                throw new SerDesException("Stage returned a null pipeline result");
            }
            return result;
        } catch (Throwable failure) {
            throw stageFailure(index, stage, "deserialize", failure);
        }
    }

    private static Object invokeValueCodecSerialize(ValueSerDes<?> valueCodec, Object value) {
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

    @SuppressWarnings("unchecked")
    private static <R, T> T invokeValueCodecDeserialize(
            ValueSerDes<R> valueCodec, Object data, TypeToken<T> typeToken) {
        try {
            return valueCodec.deserialize((R) data, typeToken);
        } catch (Throwable failure) {
            throw stageFailure(0, valueCodec, "deserialize", failure);
        }
    }

    private static <T> T invokeExternalValueCodecDeserialize(
            ValueSerDes<?> valueCodec, String data, TypeToken<T> typeToken) {
        try {
            return valueCodec.deserializeExternal(data, typeToken);
        } catch (Throwable failure) {
            throw stageFailure(0, valueCodec, "deserialize external payload", failure);
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

    private static void addFlattened(List<SerDesStage<?, ?>> target, SerDesStage<?, ?> stage) {
        if (stage instanceof ChainedSerDesStage<?, ?> chained) {
            target.addAll(chained.stages());
        } else {
            target.add(stage);
        }
    }
}
