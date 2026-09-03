// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.events;

import com.amazonaws.services.lambda.runtime.serialization.PojoSerializer;
import com.amazonaws.services.lambda.runtime.serialization.events.LambdaEventSerializers;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * A {@link SerDes} that uses the AWS Lambda Java runtime mappings for supported Lambda event models.
 *
 * <p>Lambda event payloads do not always follow the Java bean property names in {@code aws-lambda-java-events}. For
 * example, an SQS payload uses {@code Records} and {@code eventSourceARN}, while {@code SQSEvent} exposes
 * {@code records} and {@code eventSourceArn}. This implementation uses {@code aws-lambda-java-serialization} for those
 * event classes so durable handlers receive the same model shape as standard Java Lambda handlers.
 *
 * <p>All other values, including generic types, are handled by a delegate {@link SerDes}. The default delegate is
 * {@link JacksonSerDes}.
 */
public final class LambdaEventSerDes implements SerDes {
    private final SerDes delegate;
    private final Map<Class<?>, PojoSerializer<?>> eventSerializers = new ConcurrentHashMap<>();

    /** Creates a LambdaEventSerDes that delegates non-event values to {@link JacksonSerDes}. */
    public LambdaEventSerDes() {
        this(new JacksonSerDes());
    }

    /**
     * Creates a LambdaEventSerDes with a custom delegate for non-event values.
     *
     * @param delegate serializer used for values that are not supported Lambda event model classes
     */
    public LambdaEventSerDes(SerDes delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate SerDes cannot be null");
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }

        var valueClass = value.getClass();
        if (!LambdaEventSerializers.isLambdaSupportedEvent(valueClass.getName())) {
            return delegate.serialize(value);
        }

        try {
            var output = new ByteArrayOutputStream();
            PojoSerializer<Object> serializer = getEventSerializer(valueClass);
            serializer.toJson(value, output);
            return output.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SerDesException("Serialization failed for Lambda event type: " + valueClass.getName(), e);
        }
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        if (data == null) {
            return null;
        }

        if (!(typeToken.getType() instanceof Class<?> targetClass)
                || !LambdaEventSerializers.isLambdaSupportedEvent(targetClass.getName())) {
            return delegate.deserialize(data, typeToken);
        }

        try {
            PojoSerializer<T> serializer = getEventSerializer(targetClass);
            return serializer.fromJson(data);
        } catch (Exception e) {
            throw new SerDesException("Deserialization failed for Lambda event type: " + targetClass.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> PojoSerializer<T> getEventSerializer(Class<?> eventClass) {
        return (PojoSerializer<T>)
                eventSerializers.computeIfAbsent(eventClass, LambdaEventSerDes::createEventSerializer);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static synchronized PojoSerializer<?> createEventSerializer(Class<?> eventClass) {
        return LambdaEventSerializers.serializerFor((Class) eventClass, eventClass.getClassLoader());
    }
}
