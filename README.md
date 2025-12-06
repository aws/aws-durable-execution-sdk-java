# AWS Lambda Durable Execution Java SDK

A simple, idiomatic Java SDK for AWS Lambda Durable Executions.

## Status

🚧 **In Development** - PoC Implementation Phase

**Target:** Java 17 LTS, ~500 LOC, 30 incremental steps

## Quick Start

### 🎯 Start Here

**👉 Open `INCREMENTAL_START.md`** - Complete implementation guide in 30 small, testable steps.

### For Developers

1. **Read** `INCREMENTAL_START.md` (5 min) - Understand the approach
2. **Follow** `INCREMENTAL_PLAN.md` - Start with Increment 1
3. **Test** after each increment - Run `mvn test`
4. **Build** incrementally - 30 steps to complete SDK

### For AI Agents

1. **Load context** - Read `.kiro/steering/context.md` for project overview
2. **Follow incremental plan** - `INCREMENTAL_PLAN.md` through `INCREMENTAL_PLAN_PART4.md`
3. **Reference design** - Use `.kiro/design/` docs for detailed explanations

## Project Structure

```
aws-durable-execution-sdk-java/
├── README.md                    # This file
├── .kiro/
│   ├── steering/                # High-level context and guidance
│   │   ├── context.md          # Complete project context (START HERE)
│   │   ├── README.md           # Document index and quick start
│   │   └── 00-DESIGN-SUMMARY.md # Design overview
│   └── design/                  # Detailed design documents
│       ├── api-design.md
│       ├── implementation-strategy.md
│       ├── handler-registration.md
│       ├── context-lifecycle.md
│       ├── step-execution.md
│       ├── execution-state.md
│       ├── serialization.md
│       ├── error-handling.md
│       └── wait-operation.md
└── (source code to be implemented)
```

## What This SDK Does

Enables Java developers to write long-running Lambda workflows (up to 1 year) with:
- ✅ Automatic checkpointing and replay
- ✅ Suspension during wait operations (no compute charges)
- ✅ Built-in retry with exponential backoff
- ✅ Simple, idiomatic Java API

## Example

```java
public class OrderProcessor extends DurableHandler<OrderEvent, OrderResult> {
    @Override
    public OrderResult handleRequest(OrderEvent event, DurableContext ctx) {
        // Step 1: Process order
        Order order = ctx.step("process", Order.class, () -> 
            processOrder(event)
        );
        
        // Step 2: Wait for payment (execution suspends)
        ctx.wait(Duration.ofMinutes(30));
        
        // Step 3: Complete order
        String confirmation = ctx.step("complete", String.class, () ->
            completeOrder(order)
        );
        
        return new OrderResult(confirmation);
    }
}
```

## Design Principles

1. **Simple things simple** - Blocking API by default
2. **Complex things possible** - Async operations when needed
3. **Idiomatic Java** - Standard patterns (Future, Callable, Builder)
4. **Leverage AWS** - Service handles heavy lifting, SDK provides API
5. **Fresh start** - Clean implementation, not a refactor

## Implementation Timeline

- **Week 1:** Core classes (interfaces, handlers, config)
- **Week 2:** Execution logic (replay, batching, suspension)
- **Week 3:** Testing and polish

## Dependencies

- Java 17 LTS
- AWS Lambda Java Core
- AWS SDK for Java (Lambda client)
- Jackson (JSON serialization)

## Documentation

All design documents are in `.kiro/`:
- **steering/** - High-level context and guidance
- **design/** - Detailed implementation specs

**Start with:** `.kiro/steering/context.md`

## License

TBD

---

**Last Updated:** December 6, 2025  
**Status:** Design Complete, Ready for Implementation
