// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.logging;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateResponse;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TestContext;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.context.BaseContext;
import software.amazon.lambda.durable.context.BaseContextImpl;

class DurableLoggerTest {
    private static final String EXECUTION_NAME = "exec-123";
    private static final String EXECUTION_OP_ID = "20dae574-53da-37a1-bfd5-b0e2e6ec715d";
    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/"
            + EXECUTION_NAME + "/" + EXECUTION_OP_ID;
    private static final String REQUEST_ID = "req-456";
    private MDCAdapter originalMdcAdapter;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        originalMdcAdapter = MDC.getMDCAdapter();
        setMdcAdapter(new BasicMDCAdapter());
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        MDC.clear();
        BaseContextImpl.setCurrentContext(null);
        DurableLogger.detachContext();
        setMdcAdapter(originalMdcAdapter);
    }

    @Test
    void logsWhenNotReplaying() {
        var recordingLogger = new RecordingLogger();
        var logger = new DurableLogger(recordingLogger.delegate());
        var replaying = new AtomicBoolean(false);

        withContext(
                createDurableContext(replaying, LoggerConfig.defaults(), REQUEST_ID),
                () -> logger.info("test message"));

        assertEquals(1, recordingLogger.calls().size());
        assertEquals("info", recordingLogger.calls().get(0).methodName());
        assertEquals("test message", recordingLogger.calls().get(0).message());
    }

    @Test
    void suppressesLogsWhenReplayingAndSuppressionEnabled() {
        var recordingLogger = new RecordingLogger();
        var logger = new DurableLogger(recordingLogger.delegate());
        var replaying = new AtomicBoolean(true);

        withContext(createDurableContext(replaying, LoggerConfig.defaults(), REQUEST_ID), () -> {
            logger.trace("suppressed");
            logger.info("should be suppressed");
            logger.debug("also suppressed");
            logger.warn("suppressed too");
            logger.error("even errors suppressed");
        });

        assertTrue(recordingLogger.calls().isEmpty());
    }

    @Test
    void logsWhenReplayingButSuppressionDisabled() {
        var recordingLogger = new RecordingLogger();
        var logger = new DurableLogger(recordingLogger.delegate());
        var replaying = new AtomicBoolean(true);

        withContext(
                createDurableContext(replaying, LoggerConfig.withReplayLogging(), REQUEST_ID),
                () -> logger.info("should log during replay"));

        assertEquals(1, recordingLogger.calls().size());
        assertEquals("should log during replay", recordingLogger.calls().get(0).message());
    }

    @Test
    void setsExecutionMdcOnFirstLog() {
        var logger = new DurableLogger(new RecordingLogger().delegate());
        var replaying = new AtomicBoolean(false);

        BaseContextImpl.setCurrentContext(createDurableContext(replaying, LoggerConfig.defaults(), REQUEST_ID));
        DurableLogger.attachContext();
        try {
            logger.info("test");

            assertEquals(EXECUTION_ARN, MDC.get(DurableLogger.MDC_EXECUTION_ARN));
            assertEquals(REQUEST_ID, MDC.get(DurableLogger.MDC_REQUEST_ID));
        } finally {
            DurableLogger.detachContext();
        }
    }

    @Test
    void setStepThreadPropertiesSetsMdc() {
        var logger = new DurableLogger(new RecordingLogger().delegate());
        var replaying = new AtomicBoolean(false);

        BaseContextImpl.setCurrentContext(
                createStepContext(replaying, LoggerConfig.defaults(), REQUEST_ID, "op-1", "validateOrder", 2));
        DurableLogger.attachContext();
        try {
            logger.info("step log");

            assertEquals("op-1", MDC.get(DurableLogger.MDC_OPERATION_ID));
            assertEquals("validateOrder", MDC.get(DurableLogger.MDC_OPERATION_NAME));
            assertEquals("2", MDC.get(DurableLogger.MDC_ATTEMPT));
        } finally {
            DurableLogger.detachContext();
        }
    }

    @Test
    void clearThreadPropertiesRemovesMdc() {
        var logger = new DurableLogger(new RecordingLogger().delegate());
        var replaying = new AtomicBoolean(false);

        BaseContextImpl.setCurrentContext(createDurableContext(replaying, LoggerConfig.defaults(), REQUEST_ID));
        DurableLogger.attachContext();
        logger.info("test");

        DurableLogger.detachContext();

        assertNull(MDC.get(DurableLogger.MDC_EXECUTION_ARN));
        assertNull(MDC.get(DurableLogger.MDC_REQUEST_ID));
    }

    @Test
    void replayModeTransitionAllowsSubsequentLogs() {
        var recordingLogger = new RecordingLogger();
        var logger = new DurableLogger(recordingLogger.delegate());
        var replaying = new AtomicBoolean(true);
        var context = createDurableContext(replaying, LoggerConfig.defaults(), REQUEST_ID);

        withContext(context, () -> logger.info("suppressed"));
        assertTrue(recordingLogger.calls().isEmpty());

        replaying.set(false);
        withContext(context, () -> logger.info("logged after transition"));

        assertEquals(1, recordingLogger.calls().size());
        assertEquals("logged after transition", recordingLogger.calls().get(0).message());
    }

    @Test
    void allLogLevelsDelegateCorrectly() {
        var recordingLogger = new RecordingLogger();
        var logger = new DurableLogger(recordingLogger.delegate());
        var replaying = new AtomicBoolean(false);
        var exception = new RuntimeException("test");

        withContext(createDurableContext(replaying, LoggerConfig.defaults(), REQUEST_ID), () -> {
            logger.trace("trace msg");
            logger.debug("debug msg");
            logger.info("info msg");
            logger.warn("warn msg");
            logger.error("error msg");
            logger.error("error with exception", exception);
        });

        assertEquals(
                List.of("trace", "debug", "info", "warn", "error", "error"),
                recordingLogger.calls().stream().map(LogCall::methodName).toList());
        var lastCall = recordingLogger.calls().get(recordingLogger.calls().size() - 1);
        assertEquals("error with exception", lastCall.message());
        assertSame(exception, lastCall.throwable());
    }

    @Test
    void handlesNullRequestId() {
        var logger = new DurableLogger(new RecordingLogger().delegate());
        var replaying = new AtomicBoolean(false);

        BaseContextImpl.setCurrentContext(createDurableContext(replaying, LoggerConfig.withReplayLogging(), null));
        DurableLogger.attachContext();
        try {
            logger.info("test");

            assertEquals(EXECUTION_ARN, MDC.get(DurableLogger.MDC_EXECUTION_ARN));
            assertNull(MDC.get(DurableLogger.MDC_REQUEST_ID));
        } finally {
            DurableLogger.detachContext();
        }
    }

    private static DurableContext createDurableContext(
            AtomicBoolean replaying, LoggerConfig loggerConfig, String requestId) {
        return (DurableContext) Proxy.newProxyInstance(
                DurableContext.class.getClassLoader(),
                new Class<?>[] {DurableContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getExecutionArn" -> EXECUTION_ARN;
                    case "getLambdaContext" -> requestId == null ? null : new TestContext(requestId);
                    case "getDurableConfig" -> createDurableConfig(loggerConfig);
                    case "getContextId" -> null;
                    case "getContextName" -> null;
                    case "isReplaying" -> replaying.get();
                    case "toString" -> "TestDurableContext";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static StepContext createStepContext(
            AtomicBoolean replaying,
            LoggerConfig loggerConfig,
            String requestId,
            String operationId,
            String operationName,
            int attempt) {
        return (StepContext) Proxy.newProxyInstance(
                StepContext.class.getClassLoader(),
                new Class<?>[] {StepContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getExecutionArn" -> EXECUTION_ARN;
                    case "getLambdaContext" -> requestId == null ? null : new TestContext(requestId);
                    case "getDurableConfig" -> createDurableConfig(loggerConfig);
                    case "getContextId" -> operationId;
                    case "getContextName" -> operationName;
                    case "getAttempt" -> attempt;
                    case "isReplaying" -> replaying.get();
                    case "toString" -> "TestStepContext";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void withContext(BaseContext context, Runnable action) {
        BaseContextImpl.setCurrentContext(context);
        DurableLogger.attachContext();
        try {
            action.run();
        } finally {
            DurableLogger.detachContext();
            BaseContextImpl.setCurrentContext(null);
        }
    }

    private static DurableConfig createDurableConfig(LoggerConfig loggerConfig) {
        return DurableConfig.builder()
                .withLoggerConfig(loggerConfig)
                .withDurableExecutionClient(new NoOpDurableExecutionClient())
                .build();
    }

    private static void setMdcAdapter(MDCAdapter adapter) throws ReflectiveOperationException {
        var field = MDC.class.getDeclaredField("MDC_ADAPTER");
        field.setAccessible(true);
        field.set(null, adapter);
    }

    private record LogCall(String methodName, String message, Throwable throwable) {}

    private static final class RecordingLogger {
        private final List<LogCall> calls = new ArrayList<>();
        private final Logger delegate = (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(), new Class<?>[] {Logger.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "trace", "debug", "info", "warn", "error" -> {
                            var message = args != null && args.length > 0 ? (String) args[0] : null;
                            var throwable =
                                    args != null && args.length > 1 && args[1] instanceof Throwable t ? t : null;
                            calls.add(new LogCall(method.getName(), message, throwable));
                            return null;
                        }
                        case "getName" -> {
                            return "recording-logger";
                        }
                        case "isTraceEnabled", "isDebugEnabled", "isInfoEnabled", "isWarnEnabled", "isErrorEnabled" -> {
                            return true;
                        }
                        case "toString" -> {
                            return "RecordingLogger";
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        default -> {
                            if (method.getReturnType() == boolean.class) {
                                return false;
                            }
                            return null;
                        }
                    }
                });

        private Logger delegate() {
            return delegate;
        }

        private List<LogCall> calls() {
            return calls;
        }
    }

    private static final class NoOpDurableExecutionClient implements DurableExecutionClient {
        @Override
        public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
            throw new UnsupportedOperationException("Not used in DurableLoggerTest");
        }

        @Override
        public GetDurableExecutionStateResponse getExecutionState(String arn, String checkpointToken, String marker) {
            throw new UnsupportedOperationException("Not used in DurableLoggerTest");
        }
    }
}
