// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.model.DurableExecutionInput;
import com.amazonaws.lambda.durable.serde.AwsSdkV2Module;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.ParameterizedType;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class DurableHandler<I, O> implements RequestStreamHandler {

    private final Class<I> inputType;
    private final DurableConfig config;
    private final ObjectMapper objectMapper = createObjectMapper(); // Internal ObjectMapper
    private static final Logger logger = LoggerFactory.getLogger(DurableHandler.class);

    protected DurableHandler() {
        this(null);
    }

    /**
     * Constructor for testing that accepts a pre-configured DurableConfig. This constructor is primarily intended for
     * use by testing frameworks like LocalDurableTestRunner.
     *
     * @param config Pre-configured DurableConfig, or null to use createConfiguration()
     */
    protected DurableHandler(DurableConfig config) {
        // Extract input type from generic superclass
        var superClass = getClass().getGenericSuperclass();
        if (superClass instanceof ParameterizedType paramType) {
            this.inputType = (Class<I>) paramType.getActualTypeArguments()[0];
        } else {
            throw new IllegalArgumentException("Cannot determine input type parameter");
        }
        this.config = config != null ? config : createConfiguration();
        validateConfiguration();
    }

    /**
     * Template method for creating configuration. Override this method to provide custom DurableExecutionClient,
     * SerDes, or other configuration.
     *
     * <p>The SerDes from config is used for customer data serialization (user inputs/outputs). The internal
     * ObjectMapper is used for DAR backend communication.
     *
     * <p>Example:
     *
     * <pre>{@code
     * @Override
     * protected DurableConfig createConfiguration() {
     *     return DurableConfig.builder()
     *         .withDurableExecutionClient(customClient)
     *         .withSerDes(customSerDes)  // For user data
     *         .build();
     * }
     * }</pre>
     *
     * @return DurableConfig with desired configuration
     */
    protected DurableConfig createConfiguration() {
        return DurableConfig.defaultConfig();
    }

    private void validateConfiguration() {
        if (config.getDurableExecutionClient() == null) {
            throw new IllegalStateException("DurableExecutionClient configuration failed");
        }
        if (config.getSerDes() == null) {
            throw new IllegalStateException("SerDes configuration failed");
        }
        if (config.getExecutorService() == null) {
            throw new IllegalStateException("ExecutorService configuration failed");
        }
    }

    @Override
    public final void handleRequest(InputStream inputStream, OutputStream outputStream, Context context)
            throws IOException {
        var inputString = new String(inputStream.readAllBytes());
        logger.debug("Raw input from durable handler: {}", inputString);
        var input = this.objectMapper.readValue(inputString, DurableExecutionInput.class);
        var output = DurableExecutor.execute(input, context, inputType, this::handleRequest, config);
        outputStream.write(objectMapper.writeValueAsBytes(output));
    }

    /**
     * Handle the durable execution.
     *
     * @param input User input
     * @param context Durable context for operations
     * @return Result
     */
    protected abstract O handleRequest(I input, DurableContext context);

    /**
     * Creates ObjectMapper for DAR backend communication (internal use only). This is for INTERNAL use only - handles
     * Lambda Durable Functions backend protocol.
     *
     * <p>Customer-facing serialization uses SerDes from DurableConfig.
     *
     * @return Configured ObjectMapper for DAR backend communication
     */
    public static ObjectMapper createObjectMapper() {
        var dateModule = new SimpleModule();
        dateModule.addDeserializer(Date.class, new JsonDeserializer<>() {
            @Override
            public Date deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
                    throws IOException {
                // Timestamp is a double value represent seconds since epoch.
                var timestamp = jsonParser.getDoubleValue();
                // Date expects milliseconds since epoch, so multiply by 1000.
                return new Date((long) (timestamp * 1000));
            }
        });
        dateModule.addSerializer(Date.class, new JsonSerializer<>() {
            @Override
            public void serialize(Date date, JsonGenerator jsonGenerator, SerializerProvider serializerProvider)
                    throws IOException {
                // Timestamp should be a double value representing seconds since epoch, so
                // convert from milliseconds.
                double timestamp = date.getTime() / 1000.0;
                jsonGenerator.writeNumber(timestamp);
            }
        });

        // Needed for deserialization of timestamps for some SDK v2 objects
        dateModule.addDeserializer(Instant.class, new JsonDeserializer<>() {
            private static final DateTimeFormatter TIMESTAMP_FORMATTER = new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd HH:mm:ss.SSSSSSXXX")
                    .toFormatter();

            @Override
            public Instant deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
                    throws IOException {
                if (jsonParser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                    return Instant.ofEpochMilli(jsonParser.getLongValue());
                }
                var timestampStr = jsonParser.getValueAsString();
                return Instant.from(TIMESTAMP_FORMATTER.parse(timestampStr));
            }
        });

        var baseMapper = JsonMapper.builder()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Looks pretty, and probably needed for tests to be deterministic.
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                // Data passed over the wire from the backend is UpperCamelCase
                .propertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE)
                .addModule(new JavaTimeModule())
                .addModule(dateModule)
                .build();

        baseMapper.registerModule(new AwsSdkV2Module(baseMapper));

        return baseMapper;
    }
}
