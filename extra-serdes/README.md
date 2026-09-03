# AWS Lambda Durable Execution Extra SerDes

The `aws-durable-execution-sdk-java-extra-serdes` module provides
`LambdaEventSerDes`, which uses the Java Lambda runtime serializers for event
models from `aws-lambda-java-events`. This is useful for durable handlers that
receive SQS, SNS, S3, or other supported Lambda trigger events.

The module is optional so applications that do not use Lambda trigger models do
not need the event model and runtime serialization dependencies.

## Installation

```xml
<dependency>
    <groupId>software.amazon.lambda.durable</groupId>
    <artifactId>aws-durable-execution-sdk-java-extra-serdes</artifactId>
    <version>VERSION</version>
</dependency>
```

## Configuration

```java
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.events.LambdaEventSerDes;

@Override
protected DurableConfig createConfiguration() {
    return DurableConfig.builder()
        .withSerDes(new LambdaEventSerDes())
        .build();
}
```

`LambdaEventSerDes` uses the official `aws-lambda-java-serialization` mappings
for supported Lambda event classes and delegates all other values to
`JacksonSerDes`. A custom delegate can be supplied for non-event values:

```java
new LambdaEventSerDes(customSerDes)
```
