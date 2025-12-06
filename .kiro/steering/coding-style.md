# Coding Style Guide

**Project:** AWS Lambda Durable Execution Java SDK  
**Date:** December 6, 2025

This document captures the coding conventions and practices used in this project.

---

## 🎯 Core Principles

1. **Keep it simple** - Avoid unnecessary abstraction
2. **Keep it lean** - No bloat, no unused code
3. **Keep it testable** - But only test meaningful behavior
4. **Keep it tracked** - Commit after each increment

---

## 📝 Java Style

### Use `var` for Local Variables

✅ **Do:**
```java
var ctx = new DurableContext();
var result = ctx.step("test", String.class, () -> "Hello");
var log = ctx.getCheckpointLog();
```

❌ **Don't:**
```java
DurableContext ctx = new DurableContext();
String result = ctx.step("test", String.class, () -> "Hello");
Map<Integer, Operation> log = ctx.getCheckpointLog();
```

**Why:** Cleaner, more concise, type is obvious from right side.

---

### Use Static Imports

✅ **Do:**
```java
import static org.junit.jupiter.api.Assertions.*;

@Test
void testSomething() {
    assertEquals("expected", actual);
    assertTrue(condition);
}
```

❌ **Don't:**
```java
import org.junit.jupiter.api.Assertions;

@Test
void testSomething() {
    Assertions.assertEquals("expected", actual);
    Assertions.assertTrue(condition);
}
```

**Why:** Cleaner code, avoids repetitive full paths.

**When to use:** Test assertions, factory methods, common utilities.

---

## 🏗️ Architecture

### Avoid Interfaces When Only One Implementation

✅ **Do:**
```java
// Just use concrete class
public class DurableContext {
    public <T> T step(String name, Class<T> type, Callable<T> action) {
        // ...
    }
}
```

❌ **Don't:**
```java
// Unnecessary interface
public interface DurableContext {
    <T> T step(String name, Class<T> type, Callable<T> action);
}

public class DurableContextImpl implements DurableContext {
    // ...
}
```

**Why:** 
- Less bloat (one file instead of two)
- No "Impl" suffix needed
- Still mockable with Mockito
- Add interface later if actually needed

**Exception:** Use interfaces when:
- Multiple implementations exist
- You're defining a plugin/extension point
- It's a well-known pattern (e.g., Callable, Runnable)

---

## 🧪 Testing

### Only Test Meaningful Behavior

✅ **Do:**
```java
@Test
void testReplay() {
    var ctx = new DurableContext();
    var result1 = ctx.step("test", String.class, () -> "first");
    
    var ctx2 = new DurableContext(ctx.getCheckpointLog());
    var result2 = ctx2.step("test", String.class, () -> "second");
    
    assertEquals("first", result2);  // Proves replay works
}
```

❌ **Don't:**
```java
@Test
void testOperationGetters() {
    var op = new Operation("0", "test", "result");
    
    assertEquals("0", op.getId());
    assertEquals("test", op.getName());
    assertEquals("result", op.getResult());
}
```

**Why:** 
- POJO tests add no value
- Test behavior, not getters/setters
- Focus on what could actually break

**What to test:**
- Business logic
- Integration between components
- Edge cases and error handling
- Replay behavior
- Serialization round-trips

**What NOT to test:**
- Simple POJOs with just getters/setters
- Trivial constructors
- Obvious delegations

**⚠️ Before writing a test, ask:**
- "What behavior am I testing?"
- "Could this actually break?"
- "Is this just testing a getter/constructor?"

If it's just a POJO, **skip the test!**

---

## 📦 Package Structure

### Use Descriptive Package Names

✅ **Do:**
```
com.amazonaws.lambda.durable/
├── DurableContext.java       # Public API (root)
├── DurableFuture.java         # Public API (root)
├── model/                     # Data structures
│   └── Operation.java
├── state/                     # State management
│   └── ExecutionState.java
├── config/                    # Configuration
│   ├── StepConfig.java
│   └── RetryPolicy.java
├── serde/                     # Serialization
│   ├── SerDes.java
│   └── JacksonSerDes.java
└── exception/                 # Exceptions
    └── DurableExecutionException.java
```

❌ **Don't:**
```
com.amazonaws.lambda.durable/
├── internal/                  # Too vague
├── impl/                      # What kind of impl?
├── util/                      # Dumping ground
```

**Why:** Clear, self-documenting structure.

---

## 🔄 Git Workflow

### Commit After Each Increment

✅ **Do:**
```bash
# After completing each increment
git add -A
git commit -m "Increment N complete - brief description"
```

**Example commits:**
```
Increment 10 complete - SerDes integration
Increment 11 complete - wait() operation
Increment 12 complete - DurableFuture interface
Simplify: Remove DurableFuture interface, use concrete class
Add .gitignore and remove target directory
```

**Why:**
- Easy to track progress
- Easy to revert if needed
- Clear history of what changed when
- Can review each increment independently

### Use Proper .gitignore

Always include:

```gitignore
# Maven
../../target/

# IDE
.idea/
*.iml
.vscode/

# OS
.DS_Store
```

---

## 🏗️ Constructor Patterns

### Use Constructor Injection

✅ **Do:**
```java
public class DurableContext {
    private final Map<Integer, Operation> checkpointLog;
    private final SerDes serDes;
    
    public DurableContext() {
        this(new HashMap<>(), new JacksonSerDes());
    }
    
    public DurableContext(Map<Integer, Operation> checkpointLog) {
        this(checkpointLog, new JacksonSerDes());
    }
    
    public DurableContext(Map<Integer, Operation> checkpointLog, SerDes serDes) {
        this.checkpointLog = new HashMap<>(checkpointLog);
        this.serDes = serDes;
    }
}
```

❌ **Don't:**
```java
// Exposing mutable state
public Map<Integer, Operation> getCheckpointLog() {
    return checkpointLog;  // Caller can modify!
}

// Then in test:
ctx.getCheckpointLog().putAll(otherLog);  // Hacky!
```

**Why:** 
- Explicit dependencies
- Immutable after construction
- Testable with different configurations

---

## 📐 Naming Conventions

### Be Concise and Clear

✅ **Do:**
```java
var opId = operationCounter++;
var resultJson = serDes.serialize(result);
var existing = checkpointLog.get(opId);
```

❌ **Don't:**
```java
var operationIdentifier = operationCounter++;
var serializedResultInJsonFormat = serDes.serialize(result);
var existingOperationFromCheckpointLog = checkpointLog.get(opId);
```

**Why:** Shorter names are easier to read when context is clear.

---

## 🎨 Code Organization

### Keep Classes Focused

✅ **Do:**
- One responsibility per class
- Small, focused methods
- Clear separation of concerns

❌ **Don't:**
- God classes with everything
- Methods longer than ~30 lines
- Mixed concerns (e.g., business logic + serialization)

---

## 📊 When to Refactor

### Refactor Immediately When:
- You notice unnecessary abstraction (interfaces with one impl)
- Tests are testing nothing (POJO tests)
- Code is harder to read than it should be
- You're repeating yourself (DRY violation)

### Don't Refactor When:
- It's "good enough" and works
- You're just making it "more elegant"
- It would break working code for no benefit

**Principle:** Simplicity > Elegance

---

## 🎯 Summary

**Do:**
- ✅ Use `var` for local variables
- ✅ Use static imports for cleaner code
- ✅ Avoid interfaces when only one implementation
- ✅ Test meaningful behavior only
- ✅ Commit after each increment
- ✅ Use descriptive package names
- ✅ Constructor injection over setters
- ✅ Keep it simple

**Don't:**
- ❌ Create unnecessary abstractions
- ❌ Test POJOs
- ❌ Use verbose type declarations
- ❌ Use full paths when static imports work
- ❌ Commit build artifacts (use .gitignore)
- ❌ Over-engineer

**Remember:** Code should be as simple as possible, but no simpler.

