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
| `withSerDesExecutorService()` | Optional thread pool for serialization and payload I/O | Inline on the calling thread |
| `withLoggerConfig()`        | Logger behavior configuration           | Suppress logs during replay   |
| `withPollingStrategy()`     | Backend polling strategy                | Exponential backoff: 1s base, 2x rate, FULL jitter, 10s max |
| `withCheckpointDelay()`     | How often the SDK checkpoints updates   | `Duration.ofSeconds(0)` (as soon as possible) |

The `withExecutorService()` option configures the thread pool used for running user-defined operations. Internal SDK coordination (checkpoint batching, polling) runs on an SDK-managed thread pool.

By default, SerDes calls run inline. Configure `withSerDesExecutorService()` when a SerDes performs blocking storage or
network I/O. The SerDes executor must be different from the user-operation executor because SerDes calls are
synchronous from the operation's perspective and a shared saturated pool can deadlock.

### Filesystem-backed SerDes

`FileSystemSerDes` stores operation and execution payloads on a shared durable filesystem while leaving small values
inline when configured for overflow mode:

```java
var fileSystemSerDes = FileSystemSerDes.builder(Path.of("/mnt/efs/durable-payloads"))
        .storageMode(FileSystemSerDesMode.OVERFLOW)
        .pathEncoding(FileSystemPathEncoding.HASH)
        .checkpointEnvelopeLimitBytes(512 * 1024)
        .previewConfig(PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                .include(PreviewField.anywhere("id"), PreviewField.path("status"))
                .mask(PreviewField.anywhere("email"))
                .build())
        .build();

return DurableConfig.builder()
        .withSerDes(fileSystemSerDes)
        .build();
```

`ALWAYS` writes every SDK-managed payload to a file. `OVERFLOW` stores the payload inline until the complete checkpoint
envelope exceeds the configured limit, which defaults to 255 KiB. `URI` path encoding keeps identifiers readable,
while `HASH` avoids filesystem name-length and character restrictions.

`PreviewConfig` supports include-all/exclude-all modes, exact-path or anywhere field matching, masking, and a default
4 KiB preview budget. A custom `previewGenerator(...)` remains available for non-standard preview logic.

The filesystem wrapper and the value codec are configured independently. Use `.delegate(...)` to control how values are
encoded inside files:

```java
var fileSystemSerDes = FileSystemSerDes.builder(Path.of("/mnt/efs/durable-payloads"))
        .delegate(new MyCustomSerDes())
        .build();
```

Existing operation configuration controls where filesystem storage is used. For example, an invoke can send an
ordinary JSON payload while decoding its result through filesystem storage:

```java
var config = InvokeConfig.builder()
        .payloadSerDes(new JacksonSerDes())
        .serDes(fileSystemSerDes)
        .build();
```

The same pattern applies to `StepConfig.serDes(...)`, callback, child-context, map, parallel, and wait-for-condition
configuration.

The SDK supplies a `SerDesContext` through thread-local storage during managed calls:

```java
var context = SerDesContext.getCurrentContext();
```

Custom SerDes implementations can use its durable execution ARN and entity ID for external storage. Calls use the
configured SerDes executor when present, and successful deserializations are cached for the current Lambda invocation.

Do not use Lambda's `/tmp` directory: replay can run in another execution environment. Use a shared durable mount such
as EFS. S3 Files users must account for synchronization and crash-durability behavior. A chained-invoke boundary only
requires shared storage and compatible filesystem configuration when that boundary explicitly uses `FileSystemSerDes`.

See [Serialization and Filesystem Storage](serdes.md) for envelope details, structured previews, retry configuration,
testing behavior, and operational guidance.

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
