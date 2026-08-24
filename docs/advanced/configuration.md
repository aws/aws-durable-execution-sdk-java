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
            .withExecutorService(Executors.newFixedThreadPool(10))  // Custom thread pool
            .withSerDesExecutorService(Executors.newFixedThreadPool(4)) // SerDes and payload I/O
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
| `withExecutorService()`     | Thread pool for user-defined operations | Cached daemon thread pool     |
| `withSerDesExecutorService()` | Thread pool for SerDes and payload storage I/O | Cached `durable-sdk-serdes-*` daemon thread pool |
| `withLoggerConfig()`        | Logger behavior configuration           | Suppress logs during replay   |
| `withPollingStrategy()`     | Backend polling strategy                | Exponential backoff: 1s base, 2x rate, FULL jitter, 10s max |
| `withCheckpointDelay()`     | How often the SDK checkpoints updates   | `Duration.ofSeconds(0)` (as soon as possible) |

The `withExecutorService()` option configures the thread pool used for running user-defined operations. Internal SDK coordination (checkpoint batching, polling) runs on an SDK-managed thread pool.

The `withSerDesExecutorService()` option isolates serialization and blocking payload storage from user operations and
SDK coordination. The SDK sets `SerDesContext` inside each task, clears it after the call, and caches successful
deserialization results for the current invocation.

### Filesystem-backed payload storage

The optional `aws-durable-execution-sdk-java-extra-filesystem-serdes` artifact can store delegate-serialized payloads
on a shared filesystem:

```java
var fileSystemSerDes = FileSystemSerDes.builder(Path.of("/mnt/efs/durable-payloads"))
    .storageMode(FileSystemStorageMode.OVERFLOW)
    .pathEncoding(FileSystemPathEncoding.HASH)
    .delegate(new JacksonSerDes())
    .previewGenerator(value -> Map.of("type", value.getClass().getSimpleName()))
    .build();

var retryingSerDes = new RetrySerDes(
    fileSystemSerDes,
    RetryStrategies.fixedDelay(3, Duration.ofSeconds(1)));

return DurableConfig.builder()
    .withSerDes(retryingSerDes)
    .build();
```

`ALWAYS` stores every non-null payload in a file. `OVERFLOW` keeps payloads inline until the checkpoint envelope
approaches the 256 KB service limit. `URI` produces readable escaped paths; `HASH` produces fixed-length SHA-256 path
segments.

`RetrySerDes` retries only failures marked with `RetryableSerDesException`. Filesystem read and write I/O use this
marker; malformed envelopes and delegate encoding failures fail immediately. Backoff occurs on the dedicated SerDes
executor within the current Lambda invocation, so use short, bounded retry strategies.

Do not use Lambda's ephemeral `/tmp` directory: replay can run in a different execution environment. Use a durable,
shared mount such as EFS. S3 Files can have delayed synchronization, so a runtime crash before the mount flushes may
lose recent writes; use it only when that tradeoff is acceptable. The SDK does not delete offloaded files, so configure
storage lifecycle and retention separately.

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
