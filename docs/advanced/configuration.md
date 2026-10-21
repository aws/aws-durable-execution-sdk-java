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

The factory is called once per invocation and may be called concurrently on Lambda Managed Instances. It must return a fresh executor. The invocation caller waits for actual admitted work and End before shutting that executor down, including after cancellation. Code that ignores interruption can delay this shutdown beyond the policy budget; executor termination is then observed with any cleanup time that remains. Returning an executor already leased to an active invocation is rejected. The factory and `withExecutorService()` are mutually exclusive. Both options run all user code, including plugin-enabled root handlers, steps, and child contexts. Internal SDK coordination such as checkpoint batching and polling remains on the SDK-managed executor. `toBuilder()` and the local test runner preserve the factory, including across replay/resume.

A bounded executor supplied through `withExecutorService()` remains shared. On Lambda Managed Instances, concurrent roots can occupy all of its workers while waiting for queued steps. Use the invocation factory when capacity must be isolated between native invocations. Isolation does not define the capacity required by arbitrarily nested synchronous child contexts; size or design those separately.

### Dynamic plugin loading

Dynamic plugin loading is an opt-in alternative to registering plugins in application code. Put provider JARs on the application class path, then set `DURABLE_EXECUTION_PLUGINS` to an ordered, comma-separated list of provider names:

```text
DURABLE_EXECUTION_PLUGINS=otel-invocation,com.example.audit
```

When the variable is unset or blank, the SDK does not perform provider discovery. During `DurableConfig` construction, the SDK uses `ServiceLoader` and the thread context class loader to find `DurableExecutionPluginProvider` implementations. Only named providers create plugins.

Dynamically loaded plugins run first in the order listed in `DURABLE_EXECUTION_PLUGINS`. Plugins registered through `withPlugins(...)` follow in configuration order. Both sources are additive: if the same plugin is selected dynamically and registered explicitly, both factories are registered and each produces an instance that receives lifecycle hooks. Duplicate configured provider names, duplicate discovered provider names, missing providers, provider discovery failures, and selected providers that were built against an older SDK and do not implement `createPlugin(InvocationInfo)` stop configuration with an `IllegalStateException`.

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

The service file contains the provider implementation class name. A provider is itself the per-invocation plugin factory: the SDK calls `createPlugin(InvocationInfo)` once per Lambda invocation and drops the returned instance when that invocation returns, so the instance can hold its invocation's state in plain fields. A minimal provider looks like:

```java
public final class AuditPluginProvider implements DurableExecutionPluginProvider {
    @Override
    public String getName() {
        return "com.example.audit";
    }

    @Override
    public DurableExecutionPlugin createPlugin(InvocationInfo invocationInfo) {
        return new AuditPlugin(invocationInfo.durableExecutionArn());
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

The SDK captures the Lambda invocation's remaining time once and uses monotonic
cutoffs for admission and its wait policy. Deadline exhaustion selects a retryable
invocation interruption; it is not checkpointed as a business-operation failure.
Catching an admission rejection cannot turn the stopped invocation into a
successful fallback. Completed operation results and failures remain available for
replay.

Cleanup allowances determine when the SDK requests cooperative cancellation and
stops ordinary admission. They do not make cancellation equivalent to actual task
exit. Finalization still waits for admitted user work, continuations and in-flight
checkpoint/poll transport, and runs the matching invocation End on its owner.
The runtime caller waits for that owner and
End to finish, including after a deadline or interruption. End is not moved to a
completion-observer thread or deferred until after the SDK call returns.

Interruption is recorded, remaining required cleanup still runs, and the interrupt
flag is restored. A terminal execution result acknowledged by the
service remains authoritative for a handled deadline cutoff. An accepted result
does not suppress an otherwise-propagating delivery or metadata failure, and an
available JVM fatal keeps its original identity and priority.

These cutoffs do not guarantee bounded caller return or worker reclamation.
Already-running application I/O, synchronous initial-state reads, custom clients,
plugin hooks and other code that ignores interruption can keep the caller waiting
for actual cleanup beyond the policy budget. An executor that runs the root inline,
including a saturated `CallerRunsPolicy`, can also block the runtime caller before
it reaches a timeout-aware wait. Use finite downstream I/O timeouts and executor
configurations that allow progress. Cancellation cannot undo a request already
accepted by the service; interrupted side effects still require the documented
retry and idempotency strategy.
