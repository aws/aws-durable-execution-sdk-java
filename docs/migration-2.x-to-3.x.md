# Migrating from 2.x to 3.x

This guide helps teams upgrade from the `2.x` line to `3.x`.

The `3.x` line contains one breaking change: the plugin contract is now factory-only and per-invocation. Nothing outside the plugin surface changed. If your application registers no plugins, ships no plugin provider, and consumes no plugin JAR, you can upgrade the dependency version and stop reading here.

A plugin instance used to live as long as the execution environment and serve every execution that landed on it. Under Lambda Managed Instances several executions run concurrently in one environment, so a plugin had to key its own state by execution ARN and remove those entries itself. In `3.x` the SDK creates one plugin instance per Lambda invocation and drops it when the invocation returns, so per-invocation state is a plain instance field.

## There Is No Compatibility Bridge

`3.x` removes the instance-based registration path rather than keeping it alongside the factory path. There is no deprecated overload, no adapter, and no shim.

That is deliberate. While an instance path exists, a plugin registered through it still serves several executions at once, so it still needs its ARN-keyed per-execution state and still cannot delete it. Deleting that state is the entire point of the change. Both bundled plugins had a concurrency defect in exactly that ARN-keyed code under Managed Instances, and the measured effect was records lost without any error surfacing. A bridge would have preserved the defect class it was meant to retire.

The practical consequence is that recompilation against `3.x` is mandatory. Bytecode compiled against `2.x` links against `DurableConfig$Builder.withPlugins(DurableExecutionPlugin[])`, which no longer exists, and fails at runtime with:

```text
java.lang.NoSuchMethodError: 'software.amazon.lambda.durable.DurableConfig$Builder
    software.amazon.lambda.durable.DurableConfig$Builder.withPlugins(
        software.amazon.lambda.durable.plugin.DurableExecutionPlugin[])'
```

Recompiling against `3.x` turns that runtime failure into a compile error at every call site, which is the outcome you want. `DurableExecutionPlugin` declares only default methods, so it is not a functional interface and a plugin instance cannot be silently coerced into a factory. Every direct registration site therefore fails to compile until it is updated.

## Upgrade Checklist

- Replace every `withPlugins(pluginInstance)` argument with a `DurableExecutionPluginFactory`.
- Move plugin state that must outlive one invocation out of the plugin and into the factory's enclosing scope.
- Replace ARN-keyed per-execution maps inside plugins with plain instance fields.
- Replace `DurableConfig.getPluginRunner()` with `DurableConfig.getPluginFactories()`.
- Rebuild every plugin provider JAR against `3.x` and redeploy it, including provider JARs delivered as Lambda layers.
- Update bundled OTel registrations from `new InvocationOtelPlugin(...)` to `InvocationOtelPlugin.factory(...)`.
- Confirm after deployment that each configured provider is still producing telemetry.

Useful searches before upgrading:

```bash
rg -n "withPlugins\(" .
rg -n "getPluginRunner|getPlugins\(\)" .
rg -n "DurableExecutionPluginProvider|getApiVersion|getPluginType" .
rg -n "durableExecutionArn\(\)\s*\)|ConcurrentHashMap" --glob '*Plugin*.java' .
```

## 1. Register Plugin Factories Instead of Plugin Instances

`DurableConfig.Builder.withPlugins(DurableExecutionPlugin...)` is replaced by `withPlugins(DurableExecutionPluginFactory...)`.

`DurableExecutionPluginFactory` is a `@FunctionalInterface` with one method, `DurableExecutionPlugin createPlugin(InvocationInfo invocationInfo)`. A lambda or a constructor reference satisfies it directly, so no adapter class is needed.

### Stateless plugin

For a plugin that holds no state, the change is mechanical: wrap the constructor call in a lambda.

Before:

```java
@Override
protected DurableConfig createConfiguration() {
    return DurableConfig.builder()
            .withPlugins(new LoggingPlugin())
            .build();
}
```

After:

```java
@Override
protected DurableConfig createConfiguration() {
    return DurableConfig.builder()
            .withPlugins(info -> new LoggingPlugin())
            .build();
}
```

If the plugin's constructor takes exactly one `InvocationInfo` argument, a constructor reference works instead:

```java
return DurableConfig.builder()
        .withPlugins(LoggingPlugin::new)
        .build();
```

### State that must be shared across invocations

Some plugin state belongs to the execution environment rather than to one invocation: an exporter, a connection pool, a background scheduler, a resolved configuration object. That state must not be recreated per invocation. Hold it outside the lambda, and the lambda captures it.

The handler is constructed once per execution environment, and `createConfiguration()` runs during that construction, so a handler field or a local variable in `createConfiguration()` both have execution-environment lifetime.

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

Caveat: sharing an object across invocations means it is reachable from concurrent invocations in the same environment, so it still has to be thread-safe. The change removes the need for ARN keying inside plugin instances; it does not remove the need for thread safety in the objects those instances share.

### Per-invocation state that used to be keyed by execution ARN

This is the substantive part of the migration. A `2.x` plugin instance was shared, so per-execution state had to live in a map keyed by execution ARN, and the plugin had to remove the entry itself.

Before:

```java
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

After:

```java
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

- Delete the ARN-keyed map. There is nothing left to key: the instance belongs to one invocation.
- Delete the entry-removal code in `onInvocationEnd`. The SDK drops the instance when the invocation returns.
- Move anything the map's value type held into instance fields, and assign them in the constructor from the `InvocationInfo` the factory received.
- Prefer constructor assignment over assignment in `onInvocationStart`. The SDK publishes the plugin instance to the operation, checkpoint, and user function threads with a volatile write before firing the first hook, so a field assigned in the constructor is visible to those threads without being `volatile`. A field assigned inside `onInvocationStart` has no such guarantee for a thread that already existed.
- Collections that hooks mutate still need to be concurrent. Hooks for one invocation fire on several threads.

Caveat about resumes: a plugin instance does not survive suspension. When an execution suspends on a `wait()` or a callback and later resumes, the resume is a new invocation with a new plugin instance, and any state accumulated in the previous instance is gone. State that has to be stable across the whole execution must be derivable from the `InvocationInfo` of each invocation, not accumulated. `InvocationInfo.executionStartTime()` is stable across all invocations of an execution for exactly this reason, and `InvocationInfo.operations()` carries the checkpointed operations delivered at invocation start. A sampling decision should be computed deterministically from the execution ARN rather than stored.

The `2.x` code above illustrates a second reason for the change. `OperationInfo`, `OperationEndInfo`, `UserFunctionStartInfo`, and `UserFunctionEndInfo` carry no execution ARN, so a shared plugin instance could not determine which execution an operation-level hook belonged to. With one instance per invocation, that question does not arise.

### Bundled plugins

The OTel plugin's public constructors are replaced by static factory methods:

```java
// Before
DurableConfig.builder().withPlugins(new InvocationOtelPlugin()).build();

// After
DurableConfig.builder().withPlugins(InvocationOtelPlugin.factory()).build();
```

The same applies to `ExecutionOtelPlugin` and to the overloads that take an `SdkTracerProviderBuilder` and an `OtelPluginConfig`. See the [OTel plugin README](../otel-plugin/README.md#configuration) for the full set.

`WorkflowInsight.workflowInsight(config)` now returns a `DurableExecutionPluginFactory` instead of a `DurableExecutionPlugin`, so the registration source line is unchanged:

```java
DurableConfig.builder()
        .withPlugins(WorkflowInsight.workflowInsight(config))
        .build();
```

Caveat: the source line is unchanged but the return type is not, so this call site still has to be recompiled. An un-recompiled caller fails at runtime with `NoSuchMethodError`.

## 2. `PluginRunner` and `getPluginRunner()`

`PluginRunner` was never intended as customer API, and the honest migration advice is to stop using it.

It is public because the SDK dispatches hooks to it from packages other than the one it lives in. It has no documented compatibility guarantee, and this release changed it without a deprecation cycle. Treat it as an SDK internal.

What actually changed:

- `DurableConfig.getPluginRunner()` is removed. `DurableConfig.getPluginFactories()` replaces it and returns an immutable `List<DurableExecutionPluginFactory>` in dispatch order.
- `PluginRunner.getPlugins()` is removed. A runner holds no plugin instances until `onInvocationStart(InvocationInfo)` materializes them, and there is no accessor for them.
- `PluginRunner`'s constructor takes `List<DurableExecutionPluginFactory>` instead of `List<DurableExecutionPlugin>`.
- `PluginRunner.releasePlugins()` is added. The SDK calls it when the invocation returns, which is what bounds a plugin instance's lifetime to one invocation.
- `ExecutionManager.getPluginRunner()` exists in `3.x` and returns the runner for the current invocation. It is new in this release, not a renamed `2.x` method, and `ExecutionManager` is an internal coordination class. It is reachable only through `BaseContextImpl.getExecutionManager()`, which is declared on the implementation class and not on the `DurableContext` or `BaseContext` interfaces that handlers are given.

What to do instead, by what you were trying to achieve:

- **Reading which plugins are configured.** Use `DurableConfig.getPluginFactories()`. It returns factories, not instances, because instances do not exist outside an invocation.

  ```java
  List<DurableExecutionPluginFactory> factories = config.getPluginFactories();
  ```

- **Copying plugin registration into a derived `DurableConfig`.** Read the factories and pass them back through `withPlugins(...)`. This is what the SDK's own `LocalDurableTestRunner` does:

  ```java
  var derived = DurableConfig.builder()
          .withPlugins(config.getPluginFactories().toArray(new DurableExecutionPluginFactory[0]))
          .build();
  ```

- **Firing hooks yourself in a test.** Construct the plugin directly and call its hook methods. Do not construct a `PluginRunner`. Building an `InvocationInfo` and calling `new MyPlugin(sink, info).onOperationEnd(...)` tests the plugin without depending on SDK internals. For end-to-end coverage, register the factory on a `DurableConfig` and drive it through `LocalDurableTestRunner`, which exercises the real dispatch path.

- **Reaching a plugin instance from handler code at runtime.** There is no supported way to do this, and there was none in `2.x` either. Give the plugin and the handler a shared collaborator — the same object the factory captures — and communicate through it.

## 3. Rebuild Service Providers Against 3.x

`DurableExecutionPluginProvider` now extends `DurableExecutionPluginFactory` and declares only `getName()`. A provider is therefore itself the per-invocation factory.

Removed from the interface:

- `API_VERSION`
- `getApiVersion()`
- `getPluginType()`
- the zero-argument `createPlugin()`

Discovery is unchanged: providers are still registered in `META-INF/services/software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider` and still selected by name through the `DURABLE_EXECUTION_PLUGINS` environment variable. Packaging, layer layout, ordering relative to `withPlugins(...)`, and the configuration errors that stop startup are documented in [Configuration](advanced/configuration.md#dynamic-plugin-loading) and are not repeated here.

Before:

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

After:

```java
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

The provider instance itself is created once per execution environment by `ServiceLoader`, so provider fields are the right place for environment-lifetime state. `createPlugin(InvocationInfo)` runs once per invocation.

### What happens to a provider that is not rebuilt

This is the failure mode to understand before you deploy, because the function keeps succeeding while its instrumentation stops.

A provider JAR compiled against `2.x` still loads. Its class file references nothing that `3.x` removed, so `ServiceLoader` instantiates it, `getName()` returns its name, and selection through `DURABLE_EXECUTION_PLUGINS` succeeds. Configuration therefore does not fail.

The provider does not implement `createPlugin(InvocationInfo)` — it implements the zero-argument `createPlugin()` that no longer exists on the interface. When the SDK calls the method the class does not implement, the JVM throws `AbstractMethodError`. As of this release that error is contained: the SDK logs it and skips that factory for the invocation, exactly as it does for a factory that throws an exception. The execution proceeds and completes normally.

The result is a deployment that runs correctly and emits nothing from its configured instrumentation. No execution fails, no invocation errors, and the only signal is a warning in the function's own logs, repeated once per invocation, from the `software.amazon.lambda.durable.plugin.PluginRunner` logger:

```text
WARN  software.amazon.lambda.durable.plugin.PluginRunner - Plugin factory failed; skipping it for this invocation
java.lang.AbstractMethodError: com.example.AuditPluginProvider.createPlugin(...)
```

If your instrumentation is the thing that produces your traces or audit records, losing it silently is worse than a failed deployment. Rebuild every provider JAR against `3.x` and redeploy it before or with the SDK upgrade. That includes provider JARs shipped as Lambda layers, which are versioned and deployed separately from the function package and are easy to leave behind.

Caveat: containment is what makes this quiet, and containment is not the same as no failure at all. A stale provider whose class body also references an SDK symbol that `3.x` removed can instead fail during discovery, which throws `IllegalStateException` from `DurableConfig` construction and fails loudly. Both outcomes are possible depending on what the provider's code touches; neither is a substitute for rebuilding it.

### Confirming a provider loaded

There is no log line confirming successful provider selection, so confirmation is indirect. Check all three:

1. The function's logs contain no `Plugin factory failed` warning from `PluginRunner`.
2. The provider's own output is present for a recent execution — spans in your trace backend, records at your exporter's destination, or whatever the plugin emits.
3. The deployed provider artifact is the one built against `3.x`. Check the layer version or JAR checksum you deployed, not just the version you built.

A useful pre-deployment check is to run one execution locally with the provider on the class path and `DURABLE_EXECUTION_PLUGINS` set, using `LocalDurableTestRunner`, and assert that the plugin's output appears.

## Recommended Validation After Upgrading

1. Build your application against the `3.x` dependency and fix every `withPlugins(...)` compile error. There should be one per direct registration site.
2. Rebuild every plugin provider JAR you own against `3.x`.
3. Run your test suite. Tests that constructed a plugin instance and registered it will fail to compile; tests that assert on plugin output should still pass once registration is updated.
4. Exercise one workflow that suspends and resumes, and verify the plugin output for the resumed invocation is correct. This is where accumulated per-instance state that should have been derived from `InvocationInfo` shows up as missing data.
5. Exercise one workflow with concurrent child contexts, using `parallel()` or `map()`, and verify the plugin's collections tolerate concurrent hooks.
6. If you rely on dynamic loading, deploy to a pre-production stage and confirm the provider loaded using the three checks above.
7. Grep one stage's logs for `Plugin factory failed` before promoting.
8. Check that no plugin retains state after an invocation ends. A plugin instance should have no static or shared mutable collection keyed by execution ARN left in it.

## Summary

- `withPlugins(...)` takes `DurableExecutionPluginFactory` instead of `DurableExecutionPlugin`; pass `info -> new MyPlugin()` where you passed `new MyPlugin()`
- Environment-lifetime state moves outside the factory lambda; per-invocation state becomes plain instance fields and ARN-keyed maps are deleted
- `DurableConfig.getPluginRunner()` is removed in favor of `getPluginFactories()`; `PluginRunner` is an SDK internal and should not be used
- `DurableExecutionPluginProvider` keeps only `getName()` and inherits `createPlugin(InvocationInfo)`; `API_VERSION`, `getApiVersion()`, `getPluginType()`, and the zero-argument `createPlugin()` are removed
- A provider that is not rebuilt still loads and is still selected, but produces no instrumentation and only logs a warning, so rebuild and redeploy every provider JAR
- There is no compatibility bridge, and recompilation against `3.x` is required
