// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import java.lang.reflect.InvocationTargetException;
import java.util.function.Supplier;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.utils.builder.SdkBuilder;

/**
 * Holds an exporter's transport client: an injected instance, or one created on first use from an optional AWS SDK
 * artifact. A missing artifact fails at first use with a message naming it instead of a bare linkage error.
 */
final class LazyClient<T> {
    private final String artifact;
    private final Supplier<T> factory;
    private volatile T client;

    LazyClient(T injected, String artifact, Supplier<T> factory) {
        this.client = injected;
        this.artifact = artifact;
        this.factory = factory;
    }

    /**
     * A holder for an AWS SDK sync client named by class, built with {@code builder()} and the optional region. The
     * class is looked up by name on first use, so an exporter can be built while its artifact is absent and the failure
     * surfaces at export, where the plugin isolates it.
     */
    static <T> LazyClient<T> forSdkClient(T injected, String artifact, String clientClassName, String region) {
        return new LazyClient<>(injected, artifact, () -> buildSdkClient(clientClassName, region));
    }

    T get() {
        T c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = create();
                    client = c;
                }
            }
        }
        return c;
    }

    private T create() {
        try {
            return factory.get();
        } catch (NoClassDefFoundError | MissingArtifactException e) {
            throw new IllegalStateException(
                    "Missing dependency software.amazon.awssdk:" + artifact + " required by this exporter", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T buildSdkClient(String clientClassName, String region) {
        try {
            Object builder = Class.forName(clientClassName).getMethod("builder").invoke(null);
            if (region != null) {
                ((AwsClientBuilder<?, ?>) builder).region(Region.of(region));
            }
            return (T) ((SdkBuilder<?, ?>) builder).build();
        } catch (ClassNotFoundException e) {
            throw new MissingArtifactException(e);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            throw new IllegalStateException("Failed to create " + clientClassName, cause);
        }
    }

    /** Signals that the client class could not be found. */
    private static final class MissingArtifactException extends RuntimeException {
        MissingArtifactException(Throwable cause) {
            super(cause);
        }
    }
}
