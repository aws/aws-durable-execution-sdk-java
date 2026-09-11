// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.RecordFactory;

/**
 * Optional AWS SDK artifacts must not be needed to build an exporter: a configured exporter whose artifact is absent
 * has to fail at export, inside the plugin's per-exporter isolation, with a message naming the artifact.
 */
class OptionalArtifactTest {

    /** Builds one exporter and exports one record; loaded into the isolated class loader so it links there. */
    public static final class Scenario implements Supplier<String> {
        private final String exporter;

        public Scenario(String exporter) {
            this.exporter = exporter;
        }

        /** Returns the export failure message, or throws if build or export did not behave as required. */
        @Override
        public String get() {
            InsightExporter built = build();
            try {
                built.export(RecordFactory.sample());
            } catch (IllegalStateException e) {
                return e.getMessage();
            }
            throw new AssertionError(exporter + " exported without its artifact");
        }

        private InsightExporter build() {
            switch (exporter) {
                case "DynamoDBExporter":
                    return DynamoDBExporter.builder().tableName("t").build();
                case "FirehoseExporter":
                    return FirehoseExporter.builder().deliveryStreamName("s").build();
                case "EventBridgeExporter":
                    return EventBridgeExporter.builder().build();
                case "SQSExporter":
                    return SQSExporter.builder()
                            .queueUrl("https://sqs.us-east-1.amazonaws.com/1/q")
                            .build();
                case "RedshiftExporter":
                    return RedshiftExporter.builder()
                            .workgroupName("wg")
                            .database("d")
                            .build();
                case "AuroraExporter":
                    return AuroraExporter.builder()
                            .resourceArn("r")
                            .secretArn("s")
                            .database("d")
                            .engine(AuroraExporter.Engine.MYSQL)
                            .build();
                case "S3Exporter":
                    return S3Exporter.builder().bucket("b").build();
                case "CloudWatchLogsExporter":
                    return CloudWatchLogsExporter.builder().logGroupName("g").build();
                default:
                    throw new IllegalArgumentException(exporter);
            }
        }
    }

    static Stream<Arguments> exporters() {
        return Stream.of(
                Arguments.of("DynamoDBExporter", "dynamodb", "DynamoDbClient"),
                Arguments.of("FirehoseExporter", "firehose", "FirehoseClient"),
                Arguments.of("EventBridgeExporter", "eventbridge", "EventBridgeClient"),
                Arguments.of("SQSExporter", "sqs", "SqsClient"),
                Arguments.of("RedshiftExporter", "redshiftdata", "RedshiftDataClient"),
                Arguments.of("AuroraExporter", "rdsdata", "RdsDataClient"),
                Arguments.of("S3Exporter", "s3", "S3Client"),
                Arguments.of("CloudWatchLogsExporter", "cloudwatchlogs", "CloudWatchLogsClient"));
    }

    @ParameterizedTest(name = "{0} builds without {1} and fails at export naming it")
    @MethodSource("exporters")
    @SuppressWarnings("unchecked")
    void buildsWithoutTheArtifactAndFailsAtExport(String exporter, String artifact, String clientSimpleName)
            throws Exception {
        try (URLClassLoader loader = loaderWithout(artifact)) {
            String client = "software.amazon.awssdk.services." + artifact + "." + clientSimpleName;
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName(client, false, loader),
                    "the " + artifact + " client must be hidden from this loader");

            Supplier<String> scenario = (Supplier<String>) loader.loadClass(Scenario.class.getName())
                    .getConstructor(String.class)
                    .newInstance(exporter);
            String message = scenario.get();
            assertTrue(message.contains("software.amazon.awssdk:" + artifact), message);
        }
    }

    /**
     * A child-first loader over the test classpath with one service artifact removed, so the exporter classes are
     * defined by this loader (not the parent) and cannot see that service's client.
     */
    private static URLClassLoader loaderWithout(String artifact) throws Exception {
        String prefix = File.separator + artifact + "-";
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        URL[] urls = Stream.of(classPath.split(File.pathSeparator))
                .filter(p -> !p.contains(prefix))
                .map(p -> {
                    try {
                        return Path.of(p).toUri().toURL();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toArray(URL[]::new);
        assertEquals(classPath.split(File.pathSeparator).length - 1, urls.length, "exactly one artifact jar removed");
        return new URLClassLoader(urls, null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        c = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
            }
        };
    }
}
