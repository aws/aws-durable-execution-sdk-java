# Installed OpenTelemetry API compatibility

Run after building the SDK and OTel plugin:

```sh
python3 .github/scripts/verify_otel_api_compatibility.py
```

The driver resolves real released core, testing, and plugin **2.2.1** artifacts
through Maven. It compiles one probe against the released SPI and runs fresh JVMs
with old/old, new/old, old/new, and new/new core/plugin pairs, in both OTel views,
using actual API/context **1.49.0** and **1.66.0** jars. Candidate jars come from
the reactor build, or explicit `--new-core` / `--new-plugin` paths.

The sixteen required cases verify:

- Selected core, plugin, API, and context classes load from the intended jars.
- The existing service-provider API discovers and creates a healthy plugin, with
  the same instance lifetime across suspension/resume.
- Old/old plus API 1.49 reproduces the exact `GlobalOpenTelemetry.isSet`
  `NoSuchMethodError` and customer failure, before healthy start hooks/user code.
- Fixed combinations isolate that mismatch, report a diagnostic, and preserve
  healthy hooks, handler output, and completed-step replay behavior.
- An early unsupported/uninitialized global provider does not install a no-op
  global: subsequent registration must succeed. API 1.66 then exports real
  Workflow spans; unsupported API 1.49 disables the affected instrumentation.

Resolution, compilation, timeout, and probe failures all fail the command. There
is no network-dependent skip. Logs, artifact SHA-256 hashes, negative-control
results, and the case summary are written to `target/otel-api-compatibility`.
The fixture POM is independent of the reactor and changes no production version
or dependency floor.

This matrix reproduces visible-API/classpath skew with real artifacts. Its test
marker enables the documented plugin auto-configuration path; it does **not**
claim to deploy or validate every Java-agent version. Actual agent validation
must identify the released agent version, extension jar, visible API, runtime,
and observed behavior separately. Provider registration remains fail-fast for
invalid configuration; the fixture does not introduce invocation factories.
