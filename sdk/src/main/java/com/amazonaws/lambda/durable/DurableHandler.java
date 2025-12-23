package com.amazonaws.lambda.durable;

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

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import com.amazonaws.lambda.durable.model.DurableExecutionInput;
import com.amazonaws.lambda.durable.model.DurableExecutionOutput;
import com.amazonaws.lambda.durable.serde.AwsSdkV2Module;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
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

public abstract class DurableHandler<I, O> implements RequestStreamHandler {

    private final Class<I> inputType;
    private final DurableExecutionClient client;
    private final ObjectMapper objectMapper = createObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(DurableHandler.class);

    protected DurableHandler() {
        this(null);
    }

    @SuppressWarnings("unchecked")
    protected DurableHandler(DurableExecutionClient client) {
        // Extract input type from generic superclass
        var superClass = getClass().getGenericSuperclass();
        if (superClass instanceof ParameterizedType paramType) {
            this.inputType = (Class<I>) paramType.getActualTypeArguments()[0];
        } else {
            throw new IllegalArgumentException("Cannot determine input type parameter");
        }
        this.client = client;
    }

    @Override
    public final void handleRequest(
            InputStream inputStream,
            OutputStream outputStream,
            Context context) throws IOException {
        var inputString = new String(inputStream.readAllBytes());
        logger.debug("Raw input from durable handler: {}", inputString);
        var input = this.objectMapper.readValue(inputString, DurableExecutionInput.class);
        var output = durableExecution(context, input);
        outputStream.write(objectMapper.writeValueAsBytes(output));
    }

    private DurableExecutionOutput durableExecution(Context context, DurableExecutionInput input) {
        if (client != null) {
            return DurableExecutor.execute(input, context, inputType, this::handleRequest, client);
        } else {
            return DurableExecutor.execute(input, context, inputType, this::handleRequest);
        }
    }

    /**
     * Handle the durable execution.
     * 
     * @param input   User input
     * @param context Durable context for operations
     * @return Result
     */
    protected abstract O handleRequest(I input, DurableContext context);

    public static ObjectMapper createObjectMapper() {
        var dateModule = new SimpleModule();
        dateModule.addDeserializer(Date.class, new JsonDeserializer<>() {
            @Override
            public Date deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
                    throws IOException {
                // Timestamp is a double value represent seconds since epoch.
                double timestamp = jsonParser.getDoubleValue();
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
                String timestampStr = jsonParser.getValueAsString();
                return Instant.from(TIMESTAMP_FORMATTER.parse(timestampStr));
            }
        });

        var baseMapper = JsonMapper.builder()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

                // Looks pretty, and probably needed for tests to be deterministic.
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

                // Data passed over the wire from the backend is UpperCamelCase but fields in
                // our Coral-generated are regularCamelCase.
                .propertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE)
                .addModule(new JavaTimeModule())
                .addModule(dateModule)
                .build();

        baseMapper.registerModule(new AwsSdkV2Module(baseMapper));

        return baseMapper;
    }
}
