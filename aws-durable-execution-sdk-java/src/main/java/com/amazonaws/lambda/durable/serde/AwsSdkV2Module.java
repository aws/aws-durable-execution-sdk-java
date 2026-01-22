// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.serde;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;

public class AwsSdkV2Module extends SimpleModule {

    /**
     * List of AWS SDK v2 classes that require custom serialization/deserialization. Add new SDK classes here to
     * automatically register serializers and deserializers.
     *
     * <p>See https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/migration-serialization-changes.html
     */
    private static final List<Class<?>> SDK_CLASSES = List.of(Operation.class, ErrorObject.class);

    public AwsSdkV2Module() {
        super("AwsSdkV2Module");

        // Register serializers and deserializers for all SDK classes
        for (Class<?> sdkClass : SDK_CLASSES) {
            registerSdkClass(sdkClass);
        }
    }

    private <T> void registerSdkClass(Class<T> sdkClass) {
        addDeserializer(sdkClass, new SdkDeserializer<>(sdkClass));
        addSerializer(sdkClass, new SdkSerializer<>());
    }

    private static class SdkDeserializer<T> extends JsonDeserializer<T> {
        private final Class<T> sdkClass;

        SdkDeserializer(Class<T> sdkClass) {
            this.sdkClass = sdkClass;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            try {
                // Call serializableBuilderClass() method on the SDK class
                Method serializableBuilderClassMethod = sdkClass.getMethod("serializableBuilderClass");
                serializableBuilderClassMethod.setAccessible(true);
                Class<?> builderClass = (Class<?>) serializableBuilderClassMethod.invoke(null);

                // Deserialize to builder using treeToValue (avoids double parsing via toString())
                Object builder = p.readValueAs(builderClass);
                Method buildMethod = builderClass.getMethod("build");
                buildMethod.setAccessible(true);
                return (T) buildMethod.invoke(builder);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                throw new IOException(
                        "Failed to deserialize " + sdkClass.getSimpleName() + " using AWS SDK v2 builder pattern", e);
            }
        }
    }

    private static class SdkSerializer<T> extends JsonSerializer<T> {
        @Override
        public void serialize(T value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            try {
                // Call toBuilder() method on the SDK object
                Method toBuilderMethod = value.getClass().getMethod("toBuilder");
                toBuilderMethod.setAccessible(true);
                Object builder = toBuilderMethod.invoke(value);

                // Serialize the builder
                serializers.defaultSerializeValue(builder, gen);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                throw new IOException(
                        "Failed to serialize " + value.getClass().getSimpleName() + " using AWS SDK v2 builder pattern",
                        e);
            }
        }
    }
}
