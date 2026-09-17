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
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.LocalHttpServer;
import software.amazon.lambda.durable.insight.RecordFactory;

/**
 * Optional AWS SDK artifacts must not be needed to build an exporter: a configured exporter whose artifact is absent
 * has to fail at export, inside the plugin's per-exporter isolation, with a message naming the artifact.
 */
class OptionalArtifactTest {

    /** Builds one exporter and exports one record; loaded into the isolated class loader so it links there. */
    public static final class Scenario implements Supplier<String> {
        static final String EXPORTED = "exported";

        private final String exporter;
        private final String endpoint;

        public Scenario(String exporter, String endpoint) {
            this.exporter = exporter;
            this.endpoint = endpoint;
        }

        /** Returns the export failure message, or {@link #EXPORTED} when the record was delivered. */
        @Override
        public String get() {
            InsightExporter built = build();
            try {
                built.export(RecordFactory.sample());
            } catch (IllegalStateException e) {
                return e.getMessage();
            }
            return EXPORTED;
        }

        private InsightExporter build() {
            switch (exporter) {
                case "OpenSearchExporter:sigv4":
                    return OpenSearchExporter.builder()
                            .endpoint(endpoint)
                            .region("us-east-1")
                            .build();
                case "OpenSearchExporter:basic":
                    return OpenSearchExporter.builder()
                            .endpoint(endpoint)
                            .auth(OpenSearchExporter.Auth.BASIC)
                            .username("u")
                            .password("p")
                            .build();
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

    private static final String SERVICES = "software.amazon.awssdk.services.";
    private static final String OPTIONAL_AUTH = "software.amazon.awssdk:http-auth-aws and software.amazon.awssdk:auth";

    static Stream<Arguments> exporters() {
        return Stream.of(
                Arguments.of(
                        "DynamoDBExporter",
                        "dynamodb",
                        SERVICES + "dynamodb.DynamoDbClient",
                        "software.amazon.awssdk:dynamodb"),
                Arguments.of(
                        "FirehoseExporter",
                        "firehose",
                        SERVICES + "firehose.FirehoseClient",
                        "software.amazon.awssdk:firehose"),
                Arguments.of(
                        "EventBridgeExporter",
                        "eventbridge",
                        SERVICES + "eventbridge.EventBridgeClient",
                        "software.amazon.awssdk:eventbridge"),
                Arguments.of("SQSExporter", "sqs", SERVICES + "sqs.SqsClient", "software.amazon.awssdk:sqs"),
                Arguments.of(
                        "RedshiftExporter",
                        "redshiftdata",
                        SERVICES + "redshiftdata.RedshiftDataClient",
                        "software.amazon.awssdk:redshiftdata"),
                Arguments.of(
                        "AuroraExporter",
                        "rdsdata",
                        SERVICES + "rdsdata.RdsDataClient",
                        "software.amazon.awssdk:rdsdata"),
                Arguments.of("S3Exporter", "s3", SERVICES + "s3.S3Client", "software.amazon.awssdk:s3"),
                Arguments.of(
                        "CloudWatchLogsExporter",
                        "cloudwatchlogs",
                        SERVICES + "cloudwatchlogs.CloudWatchLogsClient",
                        "software.amazon.awssdk:cloudwatchlogs"),
                Arguments.of(
                        "OpenSearchExporter:sigv4",
                        "http-auth-aws",
                        "software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner",
                        OPTIONAL_AUTH),
                Arguments.of(
                        "OpenSearchExporter:sigv4",
                        "auth",
                        "software.amazon.awssdk.auth.credentials.AwsCredentialsProvider",
                        OPTIONAL_AUTH),
                Arguments.of(
                        "OpenSearchExporter:basic",
                        "auth",
                        "software.amazon.awssdk.auth.credentials.AwsCredentialsProvider",
                        Scenario.EXPORTED));
    }

    @ParameterizedTest(name = "{0} builds without {1}; export outcome: {3}")
    @MethodSource("exporters")
    @SuppressWarnings("unchecked")
    void buildsWithoutTheArtifact(String exporter, String artifact, String hiddenClass, String expected)
            throws Exception {
        try (URLClassLoader loader = loaderWithout(artifact);
                LocalHttpServer server = new LocalHttpServer()) {
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName(hiddenClass, false, loader),
                    hiddenClass + " must be hidden from this loader");

            Supplier<String> scenario = (Supplier<String>) loader.loadClass(Scenario.class.getName())
                    .getConstructor(String.class, String.class)
                    .newInstance(exporter, server.url(""));
            String outcome = scenario.get();
            assertTrue(outcome.contains(expected), outcome);
            if (Scenario.EXPORTED.equals(expected)) {
                assertEquals(1, server.requests.size(), "the record reached the endpoint");
            } else {
                assertEquals(0, server.requests.size(), "nothing was sent");
            }
        }
    }

    /**
     * A child-first loader over the test classpath with one service artifact removed, so the exporter classes are
     * defined by this loader (not the parent) and cannot see that service's client.
     */
    private static URLClassLoader loaderWithout(String artifact) throws Exception {
        Pattern jar = Pattern.compile(Pattern.quote(File.separator + artifact + "-") + "\\d.*\\.jar$");
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        URL[] urls = Stream.of(classPath.split(File.pathSeparator))
                .filter(p -> !jar.matcher(p).find())
                .map(p -> {
                    try {
                        return Path.of(p).toUri().toURL();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toArray(URL[]::new);
        assertEquals(classPath.split(File.pathSeparator).length - 1, urls.length, "exactly one artifact jar removed");
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("java.")
                        || name.startsWith("javax.")
                        || name.startsWith("jdk.")
                        || name.startsWith("com.sun.")
                        || name.startsWith("sun.")) {
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
