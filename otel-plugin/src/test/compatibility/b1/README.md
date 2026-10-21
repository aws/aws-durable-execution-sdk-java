# Installed OpenTelemetry API compatibility

Run after building the current core, testing SDK, and OTel plugin:

```sh
python3 .github/scripts/verify_otel_api_compatibility.py
```

This 3.x branch compiles its service-provider probe against the current factory
API. It runs the current core, plugin, and testing SDK together in fresh JVMs,
in both OTel views, with actual OpenTelemetry API/context **1.49.0** and **1.66.0**.
The four required cases check:

- Core, plugin, testing SDK, API, and context classes load from the intended jars.
- ServiceLoader discovers real providers; configuration creates no invocation
  instances, and each initial/resumed invocation creates its own healthy plugin.
- An unsupported visible API disables the affected instrumentation with a
  diagnostic while healthy hooks, handler output, and completed-step replay work.
- Early global-provider lookup does not install a no-op global. Later registration
  succeeds, and API 1.66 exports real Workflow spans after resume.

The independent fixture POM resolves the common dependency graph from released
2.2.1 artifacts. The driver **replaces all three durable artifacts** with the
current reactor jars before compiling or running. Explicit `--new-core`,
`--new-plugin`, and `--new-testing` paths are available for an identical built tree.
No 2.x/3.x interoperability is claimed: 3.x requires rebuilt factory providers.
The 2.x branch retains its separate sixteen-case old/new compatibility matrix.

Resolution, compilation, timeout, and probe failures fail the command; there is
no network-dependent skip. Logs, jar hashes, and results go to
`target/otel-api-compatibility`. No production dependencies or versions change.
This is a visible-API test with an explicit auto-configuration marker, not a
claim of deployed Java-agent coverage.
