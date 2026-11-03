# Configured sampler acceptance probe

`run_sampler_matrix.py` runs the real OpenTelemetry autoconfiguration SDK in
fresh JVM processes. Each process receives `OTEL_TRACES_SAMPLER` and
`OTEL_TRACES_SAMPLER_ARG` through its environment. `ServiceLoader` loads the
plugin's production autoconfiguration customizer; the probe does not call the
customizer directly or mock provider configuration. The Build workflow runs this
probe on Java 17 after installing the modules.

The autoconfiguration artifact is an input to this standalone harness. It is
not a new production or Maven test dependency. Use the version of OpenTelemetry
declared in `otel-plugin/pom.xml` (currently 1.67.0):

```bash
mvn -pl otel-plugin -am install -DskipTests
mvn -pl otel-plugin dependency:build-classpath \
  -DincludeScope=test -Dmdep.outputFile=target/sampling-classpath.txt
mvn org.apache.maven.plugins:maven-dependency-plugin:3.11.0:get \
  -Dartifact=io.opentelemetry:opentelemetry-sdk-extension-autoconfigure:1.67.0 \
  -Dtransitive=false
python3 otel-plugin/src/test/compatibility/run_sampler_matrix.py \
  --core sdk/target/classes \
  --plugin otel-plugin/target/classes \
  --dependencies-classpath otel-plugin/target/sampling-classpath.txt \
  --autoconfigure "$HOME/.m2/repository/io/opentelemetry/opentelemetry-sdk-extension-autoconfigure/1.67.0/opentelemetry-sdk-extension-autoconfigure-1.67.0.jar" \
  --java-home "$JAVA_HOME"
```

The probe covers both durable views with first and resumed invocation hooks,
all six standard policies, fixed trace IDs on opposite sides of the 0.5 ratio
threshold, complete sampled/unsampled/unset parents, missing parents, and an
invalid all-zero parent. It checks Workflow, Invocation, operation, attempt,
and synthetic-root exports, canonical trace identity, context cleanup,
ordinary application span sampling, and fallback from a known dropped
same-trace ambient span. Public SDK replay and skipped-work behavior have
separate tests; this probe exercises the telemetry integration hooks.

Configuration checks include the provider's unset default
(`parentbased_always_on`), omitted ratio (1.0), ratios 0 and 1, invalid policy
names and ratio values, and an unused ratio argument. Explicit provider
builders ignore environment sampler settings. An earlier autoconfiguration
customizer can also replace the sampler before the durable customizer wraps it.

Exports stay in memory. These checks exercise ordinary application classloaders
and the global provider's obfuscated tracer-provider facade. They do not load an
ADOT Lambda layer, qualify every Java agent classloader arrangement, or establish
X-Ray ingestion or console rendering. The harness opens no network listeners
and performs no AWS calls.
