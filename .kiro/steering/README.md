# MVP Design Documents

This folder contains all design documents needed to implement the Java SDK PoC.

## Quick Start

1. **Read 00-DESIGN-SUMMARY.md** (5 min) - Overview and core decisions
2. **Read api-design.md** (15 min) - Complete API specification
3. **Read implementation-strategy.md** (20 min) - Implementation guidance
4. **Start coding!**

## Document Index

### Core Documents (Read First)
- **00-DESIGN-SUMMARY.md** - High-level overview, decisions, and roadmap
- **api-design.md** - Complete public API specification
- **implementation-strategy.md** - Java 17 implementation patterns and phases
- **handler-registration.md** - Base class vs wrapper function patterns

### Detailed Design (Reference During Implementation)
- **context-lifecycle.md** - Envelope parsing, context creation, lifecycle
- **step-execution.md** - Step replay, checkpoint, retry mechanics
- **execution-state.md** - Checkpoint batching, task tracking, suspension
- **serialization.md** - SerDes interface and Jackson implementation
- **error-handling.md** - Exception hierarchy and retry policies
- **wait-operation.md** - Timer operation and service-side completion

## Implementation Timeline

### Week 1: Core Classes
- DurableContext interface
- DurableHandler base class
- DurableExecution wrapper
- SerDes interface + Jackson
- Configuration classes

### Week 2: Execution Logic
- DurableContextImpl with replay
- ExecutionState with batching
- DurableFuture implementation
- RetryPolicy implementation

### Week 3: Testing & Polish
- Unit tests
- Integration tests
- Example handlers
- Documentation

## Target Metrics

- **Core LOC:** ~500 lines
- **Java Version:** Java 17 LTS
- **Test Coverage:** > 80%
- **Timeline:** 3 weeks to functional PoC

## Key Features

✅ Step execution with replay  
✅ Checkpoint batching (100ms window)  
✅ Exponential backoff retry  
✅ Wait operations with suspension  
✅ Async operations (DurableFuture)  
✅ Dual handler patterns (base class + wrapper)  

## Dependencies

```xml
<!-- AWS Lambda Core -->
<dependency>
    <groupId>com.amazonaws</groupId>
    <artifactId>aws-lambda-java-core</artifactId>
    <version>1.2.3</version>
</dependency>

<!-- AWS SDK for Lambda Client -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>lambda</artifactId>
    <version>2.20.0</version>
</dependency>

<!-- Jackson for JSON -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.15.0</version>
</dependency>
```

## Package Structure

```
com.amazonaws.lambda.durable/
├── DurableContext.java
├── DurableHandler.java
├── DurableExecution.java
├── DurableFuture.java
├── config/
│   ├── StepConfig.java
│   └── RetryPolicy.java
├── serde/
│   ├── SerDes.java
│   └── JacksonSerDes.java
└── internal/
    ├── DurableContextImpl.java
    ├── ExecutionState.java
    └── DurableFutureImpl.java
```

## Next Steps

1. Set up Maven/Gradle project
2. Create package structure
3. Follow implementation-strategy.md phases
4. Refer to detailed design docs as needed
5. Write tests alongside implementation

---

**Status:** Ready for Implementation  
**Last Updated:** December 6, 2025
