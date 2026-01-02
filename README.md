# AWS Lambda Durable Execution Java SDK

A Java SDK for building resilient, long-running workflows with AWS Lambda Durable Functions.

## What is it?

Durable Functions enable you to build fault-tolerant applications that can:
- Run for up to one year
- Survive interruptions and failures
- Checkpoint progress automatically
- Resume exactly where they left off

## Quick Start

```java
public class MyDurableFunction extends DurableHandler<MyRequest, String> {

    @Override
    protected String handleRequest(MyRequest input, DurableContext context) {
        // Step 1: Call external API
        var result1 = context.step(
            "call-api",
            String.class,
            () -> doSomething(input.getId())
        );
        
        // Wait 30 minutes without cost
        context.wait(Duration.ofMinutes(30));
        
        // Step 2: Process the result
        var result2 = context.step(
            "process-result",
            String.class,
            () -> processResult(result1)
        );
        
        return result2;
    }
}
```

## Key Features

- **Automatic checkpointing** - Progress is saved automatically
- **Replay safety** - Functions resume correctly after interruptions  
- **Cost-effective waits** - No charges during suspension periods

## Operations

- `step()` - Execute code with automatic retries and checkpointing
- `wait()` - Suspend execution for a specified duration

## Requirements

- Java 17+
- AWS SDK for Java v2

## Installation

```xml
<dependency>
    <groupId>com.amazonaws</groupId>
    <artifactId>aws-lambda-durable-execution</artifactId>
    <version>1.0.0</version>
</dependency>
```

## License

This project is licensed under the Apache License 2.0.
