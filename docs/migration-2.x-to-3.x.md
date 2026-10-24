# Migrating from 2.x to 3.0

`3.0` is a major release. Its breaking change is the [per-invocation plugin factory
contract](#register-plugin-factories). Upgrade the SDK and its companion plugin
artifacts to `3.x` together, including packages delivered through Lambda layers.

Applications that register no plugins, select none through `DURABLE_EXECUTION_PLUGINS`,
and ship no plugin providers or plugin JARs can update their dependencies without
changing their handler or durable operation calls. If you are upgrading from `1.x`,
first apply the [1.x to 2.x migration guide](migration-1.x-to-2.x.md).

## Why upgrade to 3.0

- **Plugin state is isolated between invocations.** Each Lambda invocation gets
  a fresh plugin instance, including concurrent invocations in Lambda Managed
  Instances. Invocation identity, spans, and operation buffers no longer share
  one plugin object or require an execution-ARN-keyed map inside it. See
  [Per-invocation state](#per-invocation-state).
- **Resource ownership is explicit.** Factories can share thread-safe exporters,
  connection pools, and configuration while keeping invocation-specific state
  on the plugins they create. Reusable resources retain their
  execution-environment lifetime. See
  [Shared state](#shared-resources).
- **Stale discovered providers fail at startup.** A provider selected through
  `DURABLE_EXECUTION_PLUGINS` that still implements the old zero-argument factory
  is rejected during configuration, with a diagnostic identifying the provider
  and its JAR. This exposes a missed provider or layer upgrade before the handler
  runs without its telemetry. Direct registrations still require validation of
  plugin output. See
  [Providers that were not rebuilt](#stale-provider-jars).

## Porting to 3.0

The changes most likely to touch your code are `withPlugins(...)`, service providers,
and OTel registrations. Use [Finding affected code](#finding-affected-code) to locate
them before upgrading.

| Core 2.x / plugin artifacts 2.x | Core 3.x / plugin artifacts 3.x |
| --- | --- |
| `withPlugins(pluginInstance)` | `withPlugins(pluginFactory)` |
| Provider `createPlugin()` | Provider `createPlugin(InvocationInfo)` |
| `new InvocationOtelPlugin(config)` registration | `InvocationOtelPlugin.factory(config)` registration |
| `new ExecutionOtelPlugin(config)` registration | `ExecutionOtelPlugin.factory(config)` registration |
| `DurableConfig.getPluginRunner()` | `DurableConfig.getPluginFactories()` |
| Instance `@ExclusivePluginGroup` metadata | Factory `getExclusiveGroup()` metadata |

### Upgrade dependencies together

The coordinated release uses `3.0.0` for the SDK and its companion artifacts. Use the
release coordinates after publication, rather than `3.0.0-SNAPSHOT`.

All three artifacts below use group ID `software.amazon.lambda.durable` and the same
release version. Update the artifacts your application uses together:

| Artifact | Version |
| --- | --- |
| `aws-durable-execution-sdk-java` | `3.0.0` |
| `aws-durable-execution-sdk-java-testing` | `3.0.0` |
| `aws-durable-execution-sdk-java-plugin-otel` | `3.0.0` |

For example, use one property for your Core and OTel dependencies:

```xml
<properties>
    <durable.sdk.version>3.0.0</durable.sdk.version>
</properties>
<dependencies>
    <dependency>
        <groupId>software.amazon.lambda.durable</groupId>
        <artifactId>aws-durable-execution-sdk-java</artifactId>
        <version>${durable.sdk.version}</version>
    </dependency>
    <dependency>
        <groupId>software.amazon.lambda.durable</groupId>
        <artifactId>aws-durable-execution-sdk-java-plugin-otel</artifactId>
        <version>${durable.sdk.version}</version>
    </dependency>
</dependencies>
```

Set the testing artifact to the same property with test scope if you use it. Java's
durable OTel plugin version is `3.0.0`; it does not follow the separate OTel `2.0.0`
package version used by the Python and JavaScript SDKs. The optional
`aws-durable-execution-sdk-java-plugin-telemetry-exporter` artifact also uses `3.0.0`.

Inspect `mvn dependency:tree` for older transitive SDK/plugin artifacts, then rebuild
your application and provider JARs. Update attached Lambda layers together with the
function bundle. Keep existing executions on a compatible deployed function version
while routing new executions to the upgraded version; preserve operation identity and
checkpoint serialization when validating a resumed workflow. Existing 2.x consumers must
continue using compatible 2.x plugin artifacts and layers until they migrate.

### Register plugin factories

`DurableConfig.Builder.withPlugins(DurableExecutionPlugin...)` is replaced by
`withPlugins(DurableExecutionPluginFactory...)`.

`DurableExecutionPluginFactory` is a `@FunctionalInterface` with one method,
`DurableExecutionPlugin createPlugin(InvocationInfo invocationInfo)`. A lambda or a
constructor reference satisfies it directly, so no adapter class is needed.

For a stateless plugin, wrap its constructor call in a lambda.

```java
// Core 2.x
@Override
protected DurableConfig createConfiguration() {
    return DurableConfig.builder()
            .withPlugins(new LoggingPlugin())
            .build();
}
```

```java
// Core 3.0
@Override
protected DurableConfig createConfiguration() {
    return DurableConfig.builder()
            .withPlugins(info -> new LoggingPlugin())
            .build();
}
```

If the plugin's constructor takes exactly one `InvocationInfo` argument, a constructor
reference works instead:

```java
return DurableConfig.builder()
        .withPlugins(LoggingPlugin::new)
        .build();
```

Recompile plugin registration code against `3.x`. Bytecode compiled against `2.x` links
against `DurableConfig$Builder.withPlugins(DurableExecutionPlugin[])`, which no longer
exists, and fails at runtime with:

```text
java.lang.NoSuchMethodError: 'software.amazon.lambda.durable.DurableConfig$Builder
    software.amazon.lambda.durable.DurableConfig$Builder.withPlugins(
        software.amazon.lambda.durable.plugin.DurableExecutionPlugin[])'
```

Recompiling against `3.x` exposes each incompatible registration as a compile error.
Update those call sites to pass factories. The old instance-registration overload is
removed; there is no compatibility adapter.

### Rebuild service providers

`DurableExecutionPluginProvider` now extends `DurableExecutionPluginFactory` and
declares only `getName()`. A provider is therefore itself the per-invocation factory.

Removed from the interface:

- `API_VERSION`
- `getApiVersion()`
- `getPluginType()`
- the zero-argument `createPlugin()`

Discovery is unchanged: providers are still registered in
`META-INF/services/software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider`
and still selected by name through the `DURABLE_EXECUTION_PLUGINS` environment variable.
Packaging, layer layout, ordering relative to `withPlugins(...)`, and the configuration
errors that stop startup are documented in
[Configuration](advanced/configuration.md#dynamic-plugin-loading) and are not repeated
here.

```java
// Core 2.x
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

```java
// Core 3.0
public final class AuditPluginProvider implements DurableExecutionPluginProvider {

    // Environment lifetime: built once, when ServiceLoader instantiates the provider.
    private final AuditSink sink = new AuditSink();

    @Override
    public String getName() {
        return "com.example.audit";
    }

    @Override
    public DurableExecutionPlugin createPlugin(InvocationInfo invocationInfo) {
        return new AuditPlugin(sink, invocationInfo);
    }
}
```

The provider instance itself is created once per execution environment by
`ServiceLoader`, so provider fields are the right place for environment-lifetime state.
`createPlugin(InvocationInfo)` runs once per invocation.

#### Stale provider JARs

A provider JAR compiled against `2.x` can still load if its class body references no
removed SDK symbols. `ServiceLoader` can instantiate it and read its name even though
its factory method is incompatible.

It does not implement `createPlugin(InvocationInfo)` — it implements the zero-argument
`createPlugin()` that no longer exists on the interface. Calling the method the class
does not implement throws `AbstractMethodError`.

`3.x` detects that at configuration time for every provider selected through
`DURABLE_EXECUTION_PLUGINS`. Selecting a stale provider throws `IllegalStateException`
from `DurableConfig` construction, so the handler fails to initialize and the invocation
fails:

```text
java.lang.IllegalStateException: Dynamic plugin configuration failed: Plugin provider 'com.example.audit'
    (com.example.AuditPluginProvider from file:/opt/java/lib/audit-plugin.jar) does not implement
    createPlugin(InvocationInfo). It was compiled against an older Durable Execution SDK whose provider
    interface declared a different createPlugin method. Rebuild the provider against this SDK version and
    redeploy it. A provider shipped as a Lambda layer is versioned and deployed separately from the function
    package, so upgrading the function's SDK dependency does not update the layer.
```

The check reads whether `createPlugin(InvocationInfo)` resolves to an abstract method on
the provider's class, and calls no provider code. A provider written against `3.x` does
not trip it, including one that declares `createPlugin` with a narrowed return type,
inherits it from an abstract base class, or inherits it as a default method from a
subinterface of `DurableExecutionPluginFactory`.

Two cases are outside this startup check:

- **A stale provider on the class path that no name in `DURABLE_EXECUTION_PLUGINS` selects.** An unselected provider is not called or checked.
- **A stale provider registered directly through `withPlugins(...)` rather than discovered.** `DurableExecutionPluginProvider` extends `DurableExecutionPluginFactory`, so a provider instance is a valid `withPlugins(...)` argument, and the instance passed can come from a stale JAR even though the call site itself was recompiled. That registration is not checked.

For a stale directly registered provider, the SDK contains `AbstractMethodError`, logs a
warning, and skips the factory for that invocation. Business execution continues without
that provider's instrumentation. Check the
`software.amazon.lambda.durable.plugin.PluginRunner` logger:

```text
WARN  software.amazon.lambda.durable.plugin.PluginRunner - Plugin factory failed; skipping it for this invocation
java.lang.AbstractMethodError: com.example.AuditPluginProvider.createPlugin(...)
```

Ordinary factory failures during an invocation are logged and skipped; configuration
errors fail startup. The fatal-error behavior is described under [Fatal invocation
failures](#fatal-invocation-failures).

Rebuild every provider JAR against `3.x` and redeploy it before or with the SDK upgrade,
including provider JARs shipped in separately versioned Lambda layers.

A provider whose class body references a removed SDK symbol can fail earlier during
discovery, with a different `IllegalStateException` from `DurableConfig` construction.
Rebuilding the provider is required in either case.

#### Verify provider output

Successful handler startup alone does not confirm that a provider emits telemetry. There
is no log line confirming successful provider selection. Check all three:

1. The function's logs contain no `Plugin factory failed` warning from `PluginRunner` and no `Dynamic plugin configuration failed` initialization error.
2. The provider's own output is present for a recent execution — spans in your trace backend, records at your exporter's destination, or whatever the plugin emits.
3. The deployed provider artifact is the one built against `3.x`. Check the layer version or JAR checksum you deployed, not just the version you built.

A useful pre-deployment check is to run one execution locally with the provider on the
class path and `DURABLE_EXECUTION_PLUGINS` set, using `LocalDurableTestRunner`, and
assert that the plugin's output appears.

### Migrate OpenTelemetry registrations

The OTel plugin's public constructors are replaced by static factory methods:

```java
// Core 2.x / OTel 2.x
DurableConfig.builder().withPlugins(new InvocationOtelPlugin()).build();

// Core 3.0 / OTel 3.0
DurableConfig.builder().withPlugins(InvocationOtelPlugin.factory()).build();
```

The same applies to `ExecutionOtelPlugin` and to the overloads that take an
`SdkTracerProviderBuilder` and an `OtelPluginConfig`. See the [OTel plugin
README](../otel-plugin/README.md#configuration) for the full set.

`DurableExecutionTelemetry.durableExecutionTelemetry(config)` now returns a
`DurableExecutionPluginFactory` instead of a `DurableExecutionPlugin`, so the
registration source line is unchanged:

```java
DurableConfig.builder()
        .withPlugins(DurableExecutionTelemetry.durableExecutionTelemetry(config))
        .build();
```

Recompile this call site even though its source is unchanged: the return type changed,
and old bytecode fails with `NoSuchMethodError`.

### Inspect and copy plugin configuration

Use the public configuration APIs to inspect or copy plugin registrations.
`PluginRunner` and `ExecutionManager` are SDK internals.

For code that used these internals:

- `DurableConfig.getPluginRunner()` is removed. `DurableConfig.getPluginFactories()` replaces it and returns an immutable `List<DurableExecutionPluginFactory>` in dispatch order.
- `PluginRunner.getPlugins()` is removed. A runner holds no plugin instances until `onInvocationStart(InvocationInfo)` materializes them, and there is no accessor for them.
- `PluginRunner`'s constructor takes `List<DurableExecutionPluginFactory>` instead of `List<DurableExecutionPlugin>`.
- `PluginRunner.releasePlugins()` is added. The SDK calls it when the invocation returns, releasing invocation-level references. A started operation retains that invocation's end-hook recipients until its owner unwinds; invocation finalization joins that actual work before End. These cleanup references do not create or share plugin instances with another invocation.
- `ExecutionManager.getPluginRunner()` exists in `3.x` and returns the runner for the current invocation. It is new in this release, not a renamed `2.x` method, and `ExecutionManager` is an internal coordination class. It is reachable only through `BaseContextImpl.getExecutionManager()`, which is declared on the implementation class and not on the `DurableContext` or `BaseContext` interfaces that handlers are given.

Use these public configuration and testing patterns:

- **Reading which plugins are configured.** Use `DurableConfig.getPluginFactories()`. It returns factories, not instances, because instances do not exist outside an invocation.

  ```java
  List<DurableExecutionPluginFactory> factories = config.getPluginFactories();
  ```

- **Copying plugin registration into a derived `DurableConfig`.** Use `config.toBuilder()` to preserve the effective factories without repeating environment provider discovery. A fresh builder discovers providers selected by `DURABLE_EXECUTION_PLUGINS` again, so adding the existing factories can duplicate them or fail exclusive-group validation. The SDK's `LocalDurableTestRunner` copies the configuration this way and overrides only the execution client:

  ```java
  var derived = config.toBuilder()
          .withDurableExecutionClient(testClient)
          .build();
  ```

- **Firing hooks yourself in a test.** Construct the plugin directly and call its hook methods. Do not construct a `PluginRunner`. Building an `InvocationInfo` and calling `new MyPlugin(sink, info).onOperationEnd(...)` tests the plugin without depending on SDK internals. For end-to-end coverage, register the factory on a `DurableConfig` and drive it through `LocalDurableTestRunner`, which exercises the real dispatch path.

- **Reaching a plugin instance from handler code at runtime.** There is no supported way to do this, and there was none in `2.x` either. Give the plugin and the handler a shared collaborator — the same object the factory captures — and communicate through it.

### Move registration metadata to the factory

Exclusive instrumentation metadata belongs to
`DurableExecutionPluginFactory.getExclusiveGroup()` in 3.x, not the per-invocation
plugin instance. Declare or forward the group on custom factory wrappers so
configuration can validate it before construction. The 2.x minor release declares
instance groups with inherited `@ExclusivePluginGroup` metadata; migrate that
declaration to the factory in 3.x so conflicts can be rejected before instances exist.
Instance annotations are not a 3.x factory registration contract.

### Finding affected code

Search application code, provider packages, deployment configuration, and tests:

```bash
rg -n "withPlugins\(" .
rg -n "getPluginRunner|getPlugins\(\)" .
rg -n "DurableExecutionPluginProvider|getApiVersion|getPluginType" .
rg -n "durableExecutionArn\(\)\s*\)|ConcurrentHashMap" --glob '*Plugin*.java' .
```

## Plugin state and invocation lifecycle

### Shared resources

Some plugin state belongs to the execution environment rather than to one invocation: an
exporter, a connection pool, a background scheduler, a resolved configuration object.
That state must not be recreated per invocation. Hold it outside the lambda, and the
lambda captures it.

The handler is constructed once per execution environment, and `createConfiguration()`
runs during that construction, so a handler field or a local variable in
`createConfiguration()` both have execution-environment lifetime.

```java
public class AuditingHandler extends DurableHandler<Order, OrderResult> {

    // Created once per execution environment, because the handler is.
    private final AuditSink sink = new AuditSink();

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder()
                .withPlugins(info -> new AuditPlugin(sink, info))
                .build();
    }

    @Override
    public OrderResult handleRequest(Order order, DurableContext ctx) {
        // Your handler logic
    }
}
```

Objects shared by concurrent invocations must still be thread-safe. Per-invocation
plugin instances isolate invocation state, while shared resources remain the factory
owner's responsibility.

### Per-invocation state

A shared `2.x` plugin kept execution-specific state in an ARN-keyed map. Move that state
into fields on each new invocation's plugin.

```java
// Core 2.x
public final class AuditPlugin implements DurableExecutionPlugin {

    private final AuditSink sink = new AuditSink();
    private final Map<String, ExecutionState> statesByArn = new ConcurrentHashMap<>();

    @Override
    public void onInvocationStart(InvocationInfo info) {
        statesByArn.put(
                info.durableExecutionArn(),
                new ExecutionState(info.executionStartTime()));
    }

    @Override
    public void onOperationEnd(OperationEndInfo info) {
        // OperationEndInfo carries no execution ARN, so this hook cannot look up
        // its own execution's entry at all.
    }

    @Override
    public void onInvocationEnd(InvocationEndInfo info) {
        var state = statesByArn.remove(info.durableExecutionArn());
        if (state != null) {
            sink.write(info.durableExecutionArn(), state.completedOperationIds());
        }
    }

    private static final class ExecutionState {
        // start time, sampling decision, accumulated operation ids, ...
    }
}
```

```java
// Core 3.0
public final class AuditPlugin implements DurableExecutionPlugin {

    // Environment lifetime: handed in by the factory, shared by every invocation.
    private final AuditSink sink;

    // Per-invocation state: plain fields, because this instance serves one invocation.
    private final String executionArn;
    private final Instant executionStartTime;
    private final List<String> completedOperationIds = new CopyOnWriteArrayList<>();

    AuditPlugin(AuditSink sink, InvocationInfo info) {
        this.sink = sink;
        this.executionArn = info.durableExecutionArn();
        this.executionStartTime = info.executionStartTime();
    }

    @Override
    public void onOperationEnd(OperationEndInfo info) {
        completedOperationIds.add(info.id());
    }

    @Override
    public void onInvocationEnd(InvocationEndInfo info) {
        sink.write(executionArn, completedOperationIds);
    }
}
```

Migration rules for this shape:

- Delete the plugin's ARN-keyed map; each instance belongs to one invocation.
- Delete the entry-removal code in `onInvocationEnd`. The SDK drops the instance when the invocation returns.
- Move anything the map's value type held into instance fields, and assign them in the constructor from the `InvocationInfo` the factory received.
- Constructors and start hooks run in registration order: construct A, start A, construct B, start B. A later factory can observe context installed by an earlier start hook. After startup, the SDK publishes the instance list with a volatile write before handler/operation dispatch; both constructor and completed start-hook assignments are visible to SDK consumers. Mutable fields updated later still need appropriate synchronization.
- Collections that hooks mutate still need to be concurrent. Hooks for one invocation fire on several threads.

A plugin instance does not survive suspension. When an execution suspends on a `wait()`
or a callback and later resumes, the resume is a new invocation with a new plugin
instance, and any state accumulated in the previous instance is gone. State that has to
be stable across the whole execution must be derivable from the `InvocationInfo` of each
invocation, not accumulated. `InvocationInfo.executionStartTime()` is stable across all
invocations of an execution for exactly this reason, and `InvocationInfo.operations()`
carries the checkpointed operations delivered at invocation start. A sampling decision
should be computed deterministically from the execution ARN rather than stored.

The `2.x` code above illustrates a second reason for the change. `OperationInfo`,
`OperationEndInfo`, `UserFunctionStartInfo`, and `UserFunctionEndInfo` carry no
execution ARN, so a shared plugin instance could not determine which execution an
operation-level hook belonged to. With one instance per invocation, that question does
not arise.

### Invocation metadata and telemetry

Invocation-local headers are stored in `InvocationInfo.xRayTraceId()`, retaining the
eighth record component introduced by the 2.x metadata fix. The same snapshot reaches
the one-argument factory and start hook before span creation, and custom
`ContextExtractor.extract(info)` overrides retain their dispatch. The old 4-, 5-, 6- and
7-argument constructors remain, defaulting the header to null. Existing compiled
constructors/accessors and seven-component record patterns remain compatible;
recompiling a seven-component record pattern requires an eighth binding. Reflection and
record equality/hash calculation include the new component. Factory metadata rejects
simultaneous durable OTel views before construction, and configuration copies preserve
effective factory registrations without repeating environment discovery.

SDK-owned synthetic roots keep the same deterministic identity, name, attributes and
checkpointed timestamps, and are exported before the first PENDING/RETRYING response
with stable recovery reexports. Sampling intent is consumed before processor re-entry.
Handler context retains valid same-trace ambient context or activates the view root,
closes on its owning thread, and follows the handler-owned lifecycle: Start, body/scope
unwinding, operation and checkpoint drain, output preparation, then End in reverse
plugin registration order. The caller waits for that lifecycle; root scope cleanup
cannot cross End through a 500 ms caller handoff. Ordinary cleanup preserves the winning
suspension/retry outcome, while observed scope-owned fatals propagate after owner
cleanup. Fatal cancellation remains cooperative: peer tasks, admitted continuations and
transport are joined before End, so non-cooperative code can delay caller return without
a hard bound. Provider resources remain owned by the environment. Chained-invoke START
checkpoints retain the released outbound propagation metadata path; collection uses the
current invocation's plugin instances.

### Fatal invocation failures

`VirtualMachineError` and `ThreadDeath` are not converted to ordinary durable failure
results. The SDK finalizes published plugins with `RETRYING`, releases invocation state,
and rethrows the fatal cause from the invocation boundary. Fatal failures from startup
or the root handler also escape the SDK's asynchronous supplier runnable, so the default
thread-pool executor terminates that worker. A supplied executor retains its own
task-exception policy. With a direct executor, rethrow is deferred to the invocation
boundary to allow finalization first. Fatal failures from `onInvocationEnd` reach both
the invocation caller and the actual asynchronous handler worker. Ordinary handler
failures and nonfatal plugin/factory containment retain their existing behavior.

Fatal operation/user-function hook errors are reported to the invocation before they are
rethrown on the hook thread. The first plugin-owned fatal cause aborts operation waits
and bypasses user-operation retry and error serialization. Fatal publication immediately
fails delayed checkpoint and polling futures, cancels their flush timer, and rejects
later submissions. Workers awaiting an at-most-once START can therefore unwind before
shutdown waits for them. New operations and queued user handlers check the signal before
dispatch and execution. With a direct executor, a fatal operation hook leaves the
operation call exceptionally, before later handler code can run; the invocation boundary
still finalizes plugins. If a user-function start hook fails fatally, successfully
started hooks receive failure cleanup in reverse order on their owner thread before
invocation finalization is signaled. Cleanup failures do not replace the original fatal
error. Unrelated user-body failures retain their existing operation semantics.
Invocation-end information remains a snapshot at dispatch: a fatal reported only during
subsequent resource shutdown is rethrown before the response, without replaying an
already delivered end hook. If an invocation-end hook itself throws a fatal error, the
remaining end hooks are still attempted once in reverse registration order with that
same snapshot. The first fatal is published immediately, before a later end hook can
block while flushing, and is rethrown after the remaining hooks finish. A fatal already
present in the end snapshot retains precedence over a later cleanup fatal. Ordinary
event hooks retain immediate fatal propagation.

## Recommended validation after upgrading

1. Build your application against the `3.x` dependency and fix every `withPlugins(...)` compile error. There should be one per direct registration site.
2. Rebuild every plugin provider JAR you own against `3.x`.
3. Run your test suite. Tests that constructed a plugin instance and registered it will fail to compile; tests that assert on plugin output should still pass once registration is updated.
4. Exercise one workflow that suspends and resumes, and verify the plugin output for the resumed invocation is correct. This is where accumulated per-instance state that should have been derived from `InvocationInfo` shows up as missing data.
5. Exercise one workflow with concurrent child contexts, using `parallel()` or `map()`, and verify the plugin's collections tolerate concurrent hooks.
6. If you rely on dynamic loading, deploy to a pre-production stage and confirm the provider loaded using the three checks above.
7. Grep one stage's logs for `Plugin factory failed` before promoting.
8. Check that no plugin retains state after an invocation ends. A plugin instance should have no static or shared mutable collection keyed by execution ARN left in it.
