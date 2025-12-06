# Context Preservation Guide

**Last Updated:** December 7, 2025  
**Current Status:** Increment 18 complete, ready for Increment 19

---

## 🎯 Quick Start for Fresh Context

### 1. Read These Files (in order)

1. **README.md** (root) - Project overview
2. **TODO.md** (this folder) - Current progress (18/30 complete)
3. **INCREMENTAL_START.md** (this folder) - Implementation approach
4. **Current increment plan** - See TODO.md for next increment

### 2. Key Context

**What we're building:**
- Java SDK for AWS Lambda Durable Executions
- Enables long-running workflows (up to 1 year)
- Automatic checkpointing and replay

**Current progress:**
- ✅ Increments 1-18 complete (~280 LOC)
- ✅ 18 tests passing
- ✅ Core abstractions in place (DurableContext, DurableExecutionClient)
- 🔄 Next: Increment 19 - Refactor DurableContext to use client

**Key decisions made:**
- Using `DurableExecutionClient` interface to abstract Lambda API
- Two implementations: `InMemoryClient` (testing) + `LambdaClient` (production)
- No retry loops in SDK - Lambda handles retries via reinvocation
- Using `OperationStatus` enum (not strings)
- Java 17 LTS, idiomatic patterns

---

## 📁 Essential Files

### Planning Documents (.kiro/planning/)

1. **TODO.md** - Progress tracker with checklist
2. **INCREMENTAL_START.md** - Implementation philosophy
3. **INCREMENTAL_PLAN.md** - Part 1 (Increments 1-7) ✅ Complete
4. **INCREMENTAL_PLAN_PART2.md** - Part 2 (Increments 8-14) ✅ Complete
5. **INCREMENTAL_PLAN_REVISED.md** - Part 3 (Increments 17-22) 🔄 In Progress
6. **INCREMENTAL_PLAN_REVISED_PART4.md** - Part 4 (Increments 23-30) ⏳ Pending

### Reference Documents (.kiro/steering/)

1. **context.md** - Complete project context
2. **00-DESIGN-SUMMARY.md** - Design overview
3. **LANGUAGE_SDK_SPECIFICATION.md** - SDK requirements (official spec)
4. **CONTRACT.MD** - Lambda API specification
5. **coding-style.md** - Coding conventions

### Root Files

1. **README.md** - Project overview
2. **CONTRACT_ALIGNMENT.md** - Shows we match Lambda API

---

## 🔑 Critical Context Points

### 1. Why We Revised the Plan

**Original plan (Increments 15-16):**
- Had in-memory simulation with retry loops
- Didn't match real Lambda behavior

**Revised plan (Increments 17+):**
- Abstract Lambda API behind `DurableExecutionClient`
- Remove retry loops - Lambda handles retries
- Parse envelope format properly
- Proper checkpoint operations (START, SUCCEED, FAIL)

### 2. Current Architecture

```
User Code
    ↓
DurableContext (public API)
    ↓
DurableExecutionClient (interface)
    ↓
InMemoryClient (testing) OR LambdaClient (production)
    ↓
Lambda Durable Execution API
```

### 3. Key Classes

**Completed:**
- `DurableContext` - Main API (step, wait, stepAsync)
- `DurableFuture` - Async operation handle
- `Operation` - Checkpoint log entry (with OperationStatus enum)
- `OperationUpdate` - Checkpoint update request
- `SerDes` / `JacksonSerDes` - Serialization
- `RetryPolicy` - Retry configuration
- `DurableExecutionClient` - API abstraction interface
- `InMemoryDurableExecutionClient` - Testing implementation

**Next to implement (Increment 19):**
- Refactor `DurableContext` to use `DurableExecutionClient`
- Remove direct checkpoint log manipulation
- Use client.checkpoint() and client.getExecutionState()

### 4. Important Patterns

**Operation Status:**
```java
// Use enum, not strings
OperationStatus.SUCCEEDED  // ✅ Good
"SUCCEEDED"                // ❌ Bad
```

**Checkpoint Flow:**
```java
// Create update
var update = new OperationUpdate(id, "STEP", "START", name, null);

// Checkpoint via client
String newToken = client.checkpoint(arn, token, List.of(update));

// Client returns updated operation with status
```

**No Retry Loops:**
```java
// ❌ Don't do this
for (int attempt = 0; attempt < maxAttempts; attempt++) {
    try { ... } catch { ... }
}

// ✅ Do this instead
try {
    client.checkpoint(arn, token, START);
    result = action.call();
    client.checkpoint(arn, token, SUCCEED);
} catch (Exception e) {
    client.checkpoint(arn, token, FAIL);
    throw e;  // Lambda reinvokes
}
```

---

## 📊 Progress Summary

### Completed (Increments 1-18)

**Part 1: Foundation (1-7)**
- ✅ Project setup
- ✅ DurableContext interface
- ✅ Basic step execution
- ✅ Operation counter
- ✅ Operation model
- ✅ In-memory checkpoint log
- ✅ Basic replay

**Part 2: Serialization & Async (8-14)**
- ✅ Jackson dependency
- ✅ SerDes interface
- ✅ JacksonSerDes implementation
- ✅ SerDes integration
- ✅ Wait operation
- ✅ DurableFuture
- ✅ Async execution (stepAsync)

**Part 3: API Abstraction (15-18)**
- ✅ RetryPolicy (simplified)
- ✅ Retry in step() (will be removed in 19-20)
- ✅ DurableExecutionClient interface
- ✅ InMemoryDurableExecutionClient
- ✅ OperationUpdate model
- ✅ OperationStatus enum

### Next Steps (Increments 19-30)

**Part 3: Lambda API Integration (19-22)**
- 🔄 Refactor DurableContext to use client
- ⏳ Remove retry loops
- ⏳ Envelope parsing
- ⏳ DurableHandler base class

**Part 4: Production Features (23-30)**
- ⏳ LambdaDurableExecutionClient (real AWS SDK)
- ⏳ Checkpoint batching
- ⏳ Enhanced error handling
- ⏳ Structured logging
- ⏳ Documentation
- ⏳ Final polish

---

## 🚀 How to Resume

### For AI Agent

1. Load this file first
2. Read TODO.md for current status
3. Check the next increment in the plan
4. Review relevant design docs if needed
5. Continue implementation

### For Human Developer

1. Read README.md
2. Check TODO.md for progress
3. Follow the current increment plan
4. Run `mvn test` after each change
5. Commit after each increment

---

## 🔍 Common Questions

**Q: Why two client implementations?**
A: `InMemoryClient` for local testing (no AWS), `LambdaClient` for production.

**Q: Why no retry loops?**
A: Lambda handles retries via reinvocation with exponential backoff. SDK just checkpoints failures.

**Q: What's the difference between Operation and OperationUpdate?**
A: `Operation` = current state (read from checkpoint log). `OperationUpdate` = change to make (sent to checkpoint API).

**Q: Why use enum for status?**
A: Type safety, no typos, better IDE support, cleaner code.

**Q: Where are the old plans?**
A: Archived in `archive/` folder. They were superseded by revised plans.

---

## 📝 Coding Conventions

See `coding-style.md` for full details. Key points:

- Use `var` for local variables
- No interfaces when only one implementation
- Only test meaningful behavior (no POJO tests)
- Commit after each increment
- Use descriptive package names
- Keep classes focused

---

## ✅ Verification Commands

```bash
# Compile
mvn clean compile

# Run tests
mvn test

# Check test count
mvn test | grep "Tests run"

# Current status: 18 tests passing
```

---

**Status:** Ready for Increment 19  
**Next:** Refactor DurableContext to use DurableExecutionClient  
**Timeline:** ~12 hours remaining to complete MVP
