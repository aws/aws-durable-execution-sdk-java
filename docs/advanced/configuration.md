## Configuration

Customize SDK behavior by overriding `createConfiguration()` in your handler:

```java
public class OrderProcessor extends DurableHandler<Order, OrderResult> {

    @Override
    protected DurableConfig createConfiguration() {
        // Custom Lambda client with connection pooling
        var lambdaClientBuilder = LambdaClient.builder()
            .httpClient(ApacheHttpClient.builder()
                .maxConnections(50)
                .connectionTimeout(Duration.ofSeconds(30))
                .build());

        return DurableConfig.builder()
            .withLambdaClientBuilder(lambdaClientBuilder)
            .withSerDes(new MyCustomSerDes())           // Custom serialization
            .withPayloadOffloader(myPayloadOffloader)   // Optional external payload storage
            .withExecutorService(Executors.newFixedThreadPool(10))  // Custom thread pool
            .withPayloadOffloadExecutorService(payloadIoExecutor)    // Blocking payload I/O
            .withLoggerConfig(LoggerConfig.withReplayLogging())     // Enable replay logs
            .build();
    }

    @Override
    protected OrderResult handleRequest(Order order, DurableContext ctx) {
        // Your handler logic
    }
}
```

| Option                      | Description                             | Default                       |
|-----------------------------|-----------------------------------------|-------------------------------|
| `withLambdaClientBuilder()` | Custom AWS Lambda client                | Auto-configured Lambda client |
| `withSerDes()`              | Serializer for step results             | Jackson with default settings |
| `withPayloadOffloader()`    | External storage for serialized user payloads | Disabled |
| `withExecutorService()`     | Thread pool for user-defined operations | Cached daemon thread pool     |
| `withPayloadOffloadExecutorService()` | Thread pool for blocking payload storage I/O | Inline on the calling thread |
| `withLoggerConfig()`        | Logger behavior configuration           | Suppress logs during replay   |
| `withPollingStrategy()`     | Backend polling strategy                | Exponential backoff: 1s base, 2x rate, FULL jitter, 10s max |
| `withCheckpointDelay()`     | How often the SDK checkpoints updates   | `Duration.ofSeconds(0)` (as soon as possible) |

The `withExecutorService()` option configures the thread pool used for running user-defined operations. Internal SDK coordination (checkpoint batching, polling) runs on an SDK-managed thread pool.

### Payload offloading

`SerDes` remains responsible for converting objects to serialized text. A `PayloadOffloader` runs after serialization
and decides whether that text remains inline or is stored externally. On replay, the SDK resolves the stored reference
before passing the serialized text back to `SerDes`.

Filesystem payload offloading is included in the core SDK artifact.

Configure a durable shared mount:

```java
import java.nio.file.Path;
import software.amazon.lambda.durable.offload.filesystem.FileSystemPathEncoding;
import software.amazon.lambda.durable.offload.filesystem.FileSystemPayloadOffloader;
import software.amazon.lambda.durable.offload.filesystem.PayloadOffloadMode;
import software.amazon.lambda.durable.offload.filesystem.PreviewConfig;
import software.amazon.lambda.durable.offload.filesystem.PreviewField;
import software.amazon.lambda.durable.offload.filesystem.PreviewMode;

var offloader = FileSystemPayloadOffloader.builder(Path.of("/mnt/efs"))
    .storageMode(PayloadOffloadMode.OVERFLOW)
    .pathEncoding(FileSystemPathEncoding.HASH)
    .previewConfig(PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
        .include(PreviewField.anywhere("id"))
        .mask(PreviewField.anywhere("email"))
        .build())
    .build();

return DurableConfig.builder()
    .withSerDes(new JacksonSerDes())
    .withPayloadOffloader(offloader)
    .build();
```

`ALWAYS` writes every serialized payload to an immutable file. `OVERFLOW` keeps payloads inline until they approach the
configured checkpoint-envelope limit. Every envelope records producer ownership and a SHA-256 digest; loads validate
the owner, path, filename, and content. The configured base path and all ancestors must already exist; payload files are
direct children of that directory. `URI` includes a bounded readable entity prefix plus an owner digest, while `HASH`
uses only the fixed-length SHA-256 owner digest. The filesystem provider must support `SecureDirectoryStream`.

The global offloader applies to root output, checkpointed step/invoke/child/map/parallel results,
wait-for-condition state, and serialized exception data. Direct Lambda input and externally submitted callback results
remain ordinary SerDes data. Chained invoke request payloads also remain normal Lambda JSON by default so standard
Lambda targets do not need SDK envelope or shared-storage support. Compatible durable callers and targets can
explicitly opt in to offloaded invoke requests. Operation configuration can override the offloader:

```java
var stepConfig = StepConfig.builder()
    .payloadOffloader(otherOffloader)
    .build();

var inlineStepConfig = StepConfig.builder()
    .payloadOffloader(PayloadOffloader.disabled())
    .build();
```

The same `payloadOffloader(...)` option is available on `InvokeConfig`, `RunInChildContextConfig`, `MapConfig`,
`ParallelConfig`, `ParallelBranchConfig`, and `WaitForConditionConfig`.

Transient storage failures can be retried explicitly:

```java
var retryingOffloader = new RetryPayloadOffloader(
    offloader,
    RetryStrategies.fixedDelay(3, Duration.ofSeconds(1)));
```

Payload I/O runs inline by default. Configure `withPayloadOffloadExecutorService(...)` when blocking I/O should use a
dedicated pool; it must not be the user-operation executor. Calls made from SDK-managed user-operation threads execute
inline even when this executor is configured, preventing deadlock when separate executor wrappers share one bounded
backing pool.

The SDK uses a versioned checkpoint envelope and continues to read payloads written by older SDK versions as raw
serialized text. Within one Lambda invocation, resolved storage data and deserialized objects use bounded weak caches
and concurrent identical loads share one in-flight operation. Garbage collection or eviction can cause a later reload.

> **Do not use Lambda `/tmp` for durable payloads.** It is local to one execution environment and might not exist on
> replay. Use a shared durable filesystem such as EFS. S3 Files can have delayed synchronization and recent writes can
> be lost if the runtime crashes before the mount flushes; use it only when that durability tradeoff is acceptable.

The SDK does not delete offloaded files. Configure storage lifecycle and retention separately, and keep the mounted
path accessible to every function environment that may replay or consume the payload. Treat each stored file reference
as a capability: restrict access to the shared base path and protect checkpoint/history data containing references with
the same controls as the payload itself.

The versioned envelope, ownership, digest, filesystem, and chained-invoke contracts are defined in
[Payload offloader wire formats](../wire-formats/payload-offloader.md).

### Dynamic plugin loading

Dynamic plugin loading is an opt-in alternative to registering plugins in application code. Put provider JARs on the application class path, then set `DURABLE_EXECUTION_PLUGINS` to an ordered, comma-separated list of provider names:

```text
DURABLE_EXECUTION_PLUGINS=otel-invocation,com.example.audit
```

When the variable is unset or blank, the SDK does not perform provider discovery. During `DurableConfig` construction, the SDK uses `ServiceLoader` and the thread context class loader to find `DurableExecutionPluginProvider` implementations. Only named providers create plugins.

Dynamically loaded plugins run first in the order listed in `DURABLE_EXECUTION_PLUGINS`. Plugins registered through `withPlugins(...)` follow in configuration order. Both sources are additive: if the same plugin type is selected dynamically and registered explicitly, both instances are registered and receive lifecycle hooks. Duplicate configured provider names, duplicate discovered provider names, missing providers, incompatible provider API versions, invalid plugin types, and provider construction failures stop configuration with an `IllegalStateException`.

To distribute a provider in a Lambda layer, package its JAR under `java/lib`:

```text
my-plugin-layer.zip
`-- java
    `-- lib
        `-- my-durable-plugin.jar
```

The provider JAR must contain:

```text
META-INF/services/software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider
```

The service file contains the provider implementation class name. A minimal provider looks like:

```java
public final class AuditPluginProvider implements DurableExecutionPluginProvider {
    @Override
    public String getName() {
        return "com.example.audit";
    }

    @Override
    public int getApiVersion() {
        return API_VERSION;
    }

    @Override
    public Class<? extends DurableExecutionPlugin> getPluginType() {
        return AuditPlugin.class;
    }

    @Override
    public DurableExecutionPlugin createPlugin() {
        return new AuditPlugin();
    }
}
```

Provider-specific settings can use namespaced environment variables. If an application shades provider JARs into one artifact, its build must preserve and merge `META-INF/services` entries.
