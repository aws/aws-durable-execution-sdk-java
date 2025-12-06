# AWS Lambda Durable Execution SDK for Java

Java SDK for building resilient multi-step applications and AI workflows with AWS Lambda Durable Functions.

## Project Structure

This is a multi-module Maven project:

```
aws-durable-execution-sdk-java/
├── pom.xml                    # Parent POM
├── sdk/                       # SDK module (published to Maven Central)
│   ├── pom.xml
│   └── src/
│       ├── main/java/         # SDK implementation
│       └── test/java/         # SDK tests
└── examples/                  # Examples module (not published)
    ├── pom.xml
    ├── README.md
    └── src/main/java/         # Example Lambda functions
```

## Building

Build all modules:

```bash
mvn clean package
```

Build without tests:

```bash
mvn clean package -DskipTests
```

Build only the SDK:

```bash
cd sdk
mvn clean package
```

Build only examples:

```bash
cd examples
mvn clean package
```

## Modules

### SDK Module (`sdk/`)

The core SDK implementation that will be published to Maven Central. Contains:
- Core durable execution primitives (step, wait, invoke, etc.)
- Checkpoint management
- Serialization/deserialization
- Testing utilities

**Artifact:** `com.amazonaws.lambda:aws-durable-execution-sdk-java:1.0.0-SNAPSHOT`

### Examples Module (`examples/`)

Example Lambda functions demonstrating SDK usage. This module:
- Depends on the local SDK module
- Includes the Maven Shade plugin to create deployable Lambda JARs
- Is NOT published to Maven Central

See [examples/README.md](examples/README.md) for details on individual examples.

## Development

### Adding SDK Features

1. Implement in `sdk/src/main/java/`
2. Add tests in `sdk/src/test/java/`
3. Build and test: `cd sdk && mvn clean test`

### Adding Examples

1. Create new handler in `examples/src/main/java/com/amazonaws/lambda/durable/examples/`
2. Implement `RequestHandler<InputType, OutputType>`
3. Use SDK operations via `DurableContext`
4. Build deployable JAR: `cd examples && mvn clean package`
5. Document in `examples/README.md`

## Requirements

- Java 17 or later
- Maven 3.6 or later

## License

Apache-2.0
