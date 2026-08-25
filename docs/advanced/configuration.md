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
            .withSerDes(new MyCustomSerDes())                    // Custom serialization
            .withExecutorService(Executors.newFixedThreadPool(10))  // Custom thread pool
            .withSerDesExecutorService(Executors.newFixedThreadPool(4)) // Optional SerDes/payload I/O pool
            .withLoggerConfig(LoggerConfig.withReplayLogging())      // Enable replay logs
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
| `withSerDesExecutorService()` | Optional thread pool for SerDes and payload storage I/O | Inline on the calling thread |
| `withLoggerConfig()`        | Logger behavior configuration           | Suppress logs during replay   |
| `withPollingStrategy()`     | Backend polling strategy                | Exponential backoff: 1s base, 2x rate, FULL jitter, 10s max |
| `withCheckpointDelay()`     | How often the SDK checkpoints updates   | `Duration.ofSeconds(0)` (as soon as possible) |

The `withExecutorService()` option configures the thread pool used for running user-defined operations. Internal SDK coordination (checkpoint batching, polling) runs on an SDK-managed thread pool.

By default, SerDes runs synchronously on the calling thread to preserve existing behavior and avoid a queue,
`CompletableFuture`, and thread-hop cost for in-memory serialization. Configure
`withSerDesExecutorService()` when a SerDes performs blocking filesystem or network I/O, or uses retry backoff. The
SerDes executor must be different from the user-operation executor to avoid deadlock when the operation pool is
saturated.

The SDK passes `SerDesContext` explicitly to every `SerDesStage` and nested `BinarySerDesStage`. It also installs the
same context on whichever thread performs the root `SerDes` call for backward compatibility with existing value
codecs, restores any previous nested context afterward, and uses a bounded weak-reference cache for successful
deserialization results during the current invocation.

### Filesystem-backed payload storage

The core SDK provides a reversible stage for storing serialized strings on a shared filesystem:

```java
var fileSystemStage = FileSystemSerDes.builder(Path.of("/mnt/efs/durable-payloads"))
    .storageMode(FileSystemStorageMode.OVERFLOW)
    .pathEncoding(FileSystemPathEncoding.HASH)
    .build();

var resilientFileSystemStage = new RetrySerDes(
    fileSystemStage,
    RetryStrategies.fixedDelay(3, Duration.ofSeconds(1)));

var binaryStage = ComposableBinarySerDesStage.builder()
    .startWith(Utf8StringBinaryCodec.INSTANCE)
    .then(compressionBinaryStage)
    .then(encryptionBinaryStage)
    .endWith(Base64StringBinaryCodec.INSTANCE)
    .build();

var serDes = new JacksonSerDes()
    .then(binaryStage)
    .then(resilientFileSystemStage)
    .then(checkpointEnvelopeStage);
var serDesExecutor = Executors.newFixedThreadPool(4);

return DurableConfig.builder()
    .withSerDes(serDes)
    .withSerDesExecutorService(serDesExecutor)
    .build();
```

Every top-level stage consumes and produces a string, so stages compose without intermediate type mismatches.
Every stage method also receives the current read-only `SerDesContext`; the same instance is propagated through the
complete pipeline and through every retry attempt.
Every stage must emit a self-identifying, normally versioned representation. On deserialization it reverses recognized
valid input, rejects recognized malformed or unsupported input, and returns unrecognized input unchanged. This lets
raw external payloads pass through the configured stages and reach the root value codec.
`ComposableBinarySerDesStage` contains an ordered chain of `BinarySerDesStage` implementations for compression,
encryption, or other `byte[]` transformations. Its `startWith(...)`, `then(...)`, and `endWith(...)` calls follow
serialization order; deserialization reverses them. Both boundaries use the customizable `StringBinaryCodec`
interface. UTF-8 and Base64 implementations are included in the core SDK, conversion occurs only around the complete
binary chain, and the outer stage adds a reserved versioned frame for reliable format recognition.

`ALWAYS` stores every non-null payload in a file. `OVERFLOW` keeps payloads inline until the checkpoint envelope
approaches the 256 KB service limit. `URI` produces readable escaped paths; `HASH` produces fixed-length SHA-256 path
segments. Files include a content hash and never overwrite data referenced by an earlier checkpoint. References are
validated against the current durable execution and entity, and symbolic-link paths are rejected.

For a `JacksonSerDes -> FileSystemSerDes` pipeline, structured preview configuration provides the same field
selection, masking, exact-path matching, and default 4 KB preview budget as the Python and TypeScript SDKs:

```java
var previewConfig = PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
    .include(PreviewField.anywhere("id"), PreviewField.path("customer.status"))
    .mask(PreviewField.anywhere("email"))
    .build();

var fileSystemStage = FileSystemSerDes.builder(Path.of("/mnt/s3/durable-payloads"))
    .previewConfig(previewConfig)
    .build();

var serDes = new JacksonSerDes().then(fileSystemStage);
```

The built-in preview configuration parses the string produced by the preceding stage as JSON. Use
`previewGenerator(...)` when the preceding stage produces another format or when fully custom preview logic is needed.

`RetrySerDes` implements `SerDesStage` and retries only failures marked with `RetryableSerDesException`. Filesystem
read and write I/O use this marker; malformed envelopes and codec failures fail immediately. Backoff occurs within the
current Lambda invocation, so use short, bounded retry strategies. Without a configured SerDes executor, filesystem
I/O and retry delays block the calling thread.

Do not use Lambda's ephemeral `/tmp` directory: replay can run in a different execution environment. Use a durable,
shared mount such as EFS or S3 Files. Payloads are published with one immutable `CREATE_NEW` write, without hard links
or renames, so the write path is compatible with S3 Files. S3 Files can have delayed synchronization, so a runtime
crash before the mount flushes may lose recent writes; use it only when that tradeoff is acceptable. The SDK does not
delete offloaded files, so configure storage lifecycle and retention separately.

The cloud and local test runners always serialize the initial Lambda invocation with a separate context-free value
codec. By default, this is the configured persisted SerDes when it is a plain value codec, or the root value codec when
the persisted SerDes is a `ComposableSerDes`; `withInputSerDes(...)` provides an explicit override. The runners never
use persisted pipeline stages to encode the initial invocation. The explicit input SerDes must therefore be a value
codec rather than a `ComposableSerDes`. When the runtime later deserializes that raw input, each persisted stage passes
it through unless its self-identifying format is present. A custom input codec must produce data that the persisted
pipeline's root value codec can decode.

Stages may follow `FileSystemSerDes` to transform its inline or file-reference envelope. `OVERFLOW` and preview-size
checks apply to the filesystem stage's output; account for any expansion introduced by later stages when staying
within the service checkpoint limit. On deserialization, every stage passes input through unchanged when its own
self-identifying format is absent, so raw external payloads can safely traverse stages on either side of the
filesystem stage.

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
