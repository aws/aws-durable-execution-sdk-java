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
| `withInvocationExecutorFactory()` | Factory for isolated per-invocation user executors | Disabled |
| `withLoggerConfig()`        | Logger behavior configuration           | Suppress logs during replay   |
| `withPollingStrategy()`     | Backend polling strategy                | Exponential backoff: 1s base, 2x rate, FULL jitter, 10s max |
| `withCheckpointDelay()`     | How often the SDK checkpoints updates   | `Duration.ofSeconds(0)` (as soon as possible) |

The `withExecutorService()` option configures a caller-owned executor shared by every invocation that uses the configuration. The SDK never shuts it down. To isolate concurrent Lambda Managed Instances invocations, configure a factory instead:

```java
DurableConfig.builder()
    .withInvocationExecutorFactory(() -> Executors.newFixedThreadPool(4))
    .build();
```

The factory is called once per invocation and may be called concurrently on Lambda Managed Instances. It must return a fresh executor. After normal handler and End completion, the invocation caller shuts that executor down and awaits termination within the remaining cleanup budget. Code that ignores interruption may outlive that budget. Returning an executor already leased to an active invocation is rejected. The factory and `withExecutorService()` are mutually exclusive. Both options run all user code, including plugin-enabled root handlers, steps, and child contexts. Internal SDK coordination such as checkpoint batching and polling remains on the SDK-managed executor. `toBuilder()` and the local test runner preserve the factory, including across replay/resume.

A bounded executor supplied through `withExecutorService()` remains shared. On Lambda Managed Instances, concurrent roots can occupy all of its workers while waiting for queued steps. Use the invocation factory when capacity must be isolated between native invocations. Isolation does not define the capacity required by arbitrarily nested synchronous child contexts; size or design those separately.

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

## Filesystem serialization

The SDK includes [Filesystem SerDes](filesystem-serdes.md), which provides
`ALWAYS` and `OVERFLOW` modes, URI/hash paths, delegate serializers, and previews. Configure it per operation with `serDes(...)`; keep the global serializer
for normal Lambda input/output. It requires a durable shared mount and external
file retention.

Custom serializers can override `serialize(value, SerDesContext)` and
`deserialize(data, typeToken, SerDesContext)` to receive stable execution/entity
identity. Existing implementations inherit defaults that call the original methods.

## Invocation deadline cleanup

The SDK captures the Lambda invocation's remaining time once and uses a monotonic
budget for its waits and cleanup. Deadline exhaustion is an invocation interruption
that can be retried; it is not checkpointed as a business-operation failure.
Completed operation results and failures remain available for replay.

Normal finalization drains invocation-owned user work before result preparation
and invocation End. Cleanup first requests cooperative cancellation when work does
not settle within its allowance, then rejects new durable admission. Required
checkpoint waits and cleanup use the remaining budget. A terminal execution result
already acknowledged by the service remains authoritative, while a JVM fatal is
never replaced by a cleanup timeout or an acknowledged-result shortcut.

Interruption does not skip the remaining cleanup attempts: the SDK records it,
continues best-effort joins and checkpoint shutdown with the remaining budget, and
restores the interrupt flag. If an uncooperative root exits after its caller has
already timed out, its original worker still runs the matching invocation End once
to restore Start-installed scopes. This eventual cleanup does not admit new durable
work or prepare another result. An End hook that has already started can itself
remain blocked; the SDK cannot forcibly stop arbitrary callbacks.

This is not an unconditional bounded-return or worker-reclamation guarantee.
Already-running application I/O, synchronous initial-state reads, custom clients,
and plugin hooks cannot be forcibly terminated. In particular, an executor that
runs the root inline, including a saturated `CallerRunsPolicy`, can block the runtime
caller before it reaches its timeout-aware wait. Use finite downstream I/O timeouts
and executor configurations that let the runtime caller make progress. Cancellation
cannot undo a request already accepted by the service; interrupted side effects
still require the documented retry and idempotency strategy.
