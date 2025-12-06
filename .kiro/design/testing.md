# Testing Package Design

**Date:** December 11, 2025  
**Status:** Design

## Problem Statement

Currently, testing utilities (`LocalDurableTestRunner`, `LocalMemoryExecutionClient`, `TestResult`) are bundled in the main SDK package. This means:

1. Production deployments include unnecessary testing code
2. Testing dependencies increase the deployment package size
3. No clear separation between production and test-only code

## Goals

1. Extract testing utilities into a separate Maven module
2. Keep production SDK lean (no testing code)
3. Maintain ease of use for developers writing tests
4. Avoid breaking changes to existing test code

## Proposed Structure

```
aws-durable-execution-sdk-java/
├── pom.xml                                    # Parent POM
├── sdk/                                       # Production SDK
│   ├── pom.xml
│   └── src/
│       ├── main/java/
│       │   └── com/amazonaws/lambda/durable/
│       │       ├── DurableContext.java
│       │       ├── DurableHandler.java
│       │       └── ... (production code only)
│       └── test/java/                         # SDK's own tests
│           └── ...
├── sdk-testing/                               # NEW: Testing utilities
│   ├── pom.xml
│   └── src/
│       └── main/java/
│           └── com/amazonaws/lambda/durable/testing/
│               ├── LocalDurableTestRunner.java
│               ├── LocalMemoryExecutionClient.java
│               └── TestResult.java
└── examples/                                  # Examples
    ├── pom.xml                                # Depends on sdk-testing
    └── ...
```

## Module Breakdown

### 1. SDK Module (`sdk/`)

**Contains:**
- All production code
- Core durable execution primitives
- Checkpoint management
- Serialization
- Exception handling

**Does NOT contain:**
- Testing utilities
- Mock implementations
- Test helpers

**Dependencies:**
- AWS SDK for Lambda
- Jackson (serialization)
- JUnit (test scope only)

### 2. SDK Testing Module (`sdk-testing/`)

**Contains:**
- `LocalDurableTestRunner` - Run handlers locally without Lambda
- `LocalMemoryExecutionClient` - In-memory execution state storage
- `TestResult` - Wrapper for test assertions
- Future: Mock implementations, test builders, assertion helpers

**Dependencies:**
- SDK module (compile scope)
- JUnit (optional, for assertion helpers)

**Artifact:**
```xml
<dependency>
    <groupId>com.amazonaws.lambda</groupId>
    <artifactId>aws-durable-execution-sdk-java-testing</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

### 3. Examples Module (`examples/`)

**Dependencies:**
```xml
<dependencies>
    <!-- Production SDK -->
    <dependency>
        <groupId>com.amazonaws.lambda</groupId>
        <artifactId>aws-durable-execution-sdk-java</artifactId>
        <version>${project.version}</version>
    </dependency>
    
    <!-- Testing utilities (test scope) -->
    <dependency>
        <groupId>com.amazonaws.lambda</groupId>
        <artifactId>aws-durable-execution-sdk-java-testing</artifactId>
        <version>${project.version}</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## Migration Plan

### Phase 1: Create Testing Module

1. Create `sdk-testing/` directory
2. Create `sdk-testing/pom.xml` with dependency on SDK
3. Move testing classes from `sdk/src/main/java/.../testing/` to `sdk-testing/src/main/java/.../testing/`
4. Update parent POM to include `sdk-testing` module

### Phase 2: Update Dependencies

1. Add `sdk-testing` dependency to `sdk/pom.xml` (test scope)
2. Add `sdk-testing` dependency to `examples/pom.xml` (test scope)
3. Update imports in test files

### Phase 3: Verify

1. Run SDK tests: `cd sdk && mvn test`
2. Run example tests: `cd examples && mvn test`
3. Build production JAR and verify size reduction
4. Verify testing utilities are NOT in production JAR

### Phase 4: Documentation

1. Update README with testing module information
2. Add testing guide
3. Update examples to show testing module usage

## Package Size Impact

**Before (estimated):**
- SDK JAR: ~500 KB (includes testing utilities)

**After (estimated):**
- SDK JAR: ~450 KB (production only)
- SDK Testing JAR: ~50 KB (testing utilities)

**Benefit:** Production deployments are ~10% smaller.

## API Compatibility

### No Breaking Changes

The testing utilities keep the same package name:
```java
// Before and after - same import
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
```

Users only need to add the testing dependency:
```xml
<dependency>
    <groupId>com.amazonaws.lambda</groupId>
    <artifactId>aws-durable-execution-sdk-java-testing</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

## Testing Module Features

### Current Features

1. **LocalDurableTestRunner** - Run handlers without Lambda
2. **LocalMemoryExecutionClient** - In-memory state storage
3. **TestResult** - Test result wrapper

### Future Enhancements

1. **Mock Builders**
   ```java
   var mockInput = DurableExecutionInputBuilder.create()
       .withArn("arn:...")
       .withOperation(...)
       .build();
   ```

2. **Assertion Helpers**
   ```java
   assertThat(result)
       .succeeded()
       .hasResult("expected")
       .completedSteps("step1", "step2", "step3");
   ```

3. **Time Simulation**
   ```java
   var runner = new LocalDurableTestRunner<>(...)
       .withTimeSimulation()
       .advanceTime(Duration.ofSeconds(10));
   ```

4. **Checkpoint Inspection**
   ```java
   var checkpoints = result.getCheckpoints();
   assertThat(checkpoints).hasSize(3);
   assertThat(checkpoints.get(0)).isStep("step1");
   ```

## Alternative Approaches Considered

### Alternative 1: Keep Testing in SDK

**Pros:**
- Simpler structure
- No additional dependency

**Cons:**
- Larger production deployments
- Testing code in production JAR
- Violates separation of concerns

**Decision:** Rejected - production code should be lean.

### Alternative 2: Separate Repository

**Pros:**
- Complete separation
- Independent versioning

**Cons:**
- More complex to maintain
- Version compatibility issues
- Harder for users to discover

**Decision:** Rejected - multi-module is simpler.

### Alternative 3: Test Scope Only (No Separate Module)

**Pros:**
- Simplest approach
- No new module

**Cons:**
- Testing utilities still in main source tree
- Can't be used by other projects
- Still included in source JAR

**Decision:** Rejected - doesn't solve the problem.

## Implementation Checklist

- [ ] Create `sdk-testing/` directory structure
- [ ] Create `sdk-testing/pom.xml`
- [ ] Move `LocalDurableTestRunner.java`
- [ ] Move `LocalMemoryExecutionClient.java`
- [ ] Move `TestResult.java`
- [ ] Update parent POM with new module
- [ ] Add `sdk-testing` dependency to `sdk/pom.xml` (test scope)
- [ ] Add `sdk-testing` dependency to `examples/pom.xml` (test scope)
- [ ] Update SDK tests imports
- [ ] Update examples tests imports
- [ ] Run all tests to verify
- [ ] Build and verify JAR sizes
- [ ] Update documentation
- [ ] Update README with testing module info

## Documentation Updates

### README.md

Add section:
```markdown
## Testing

To test your durable functions locally, add the testing module:

```xml
<dependency>
    <groupId>com.amazonaws.lambda</groupId>
    <artifactId>aws-durable-execution-sdk-java-testing</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

See [Testing Guide](TESTING.md) for details.
```

### New TESTING.md

Create comprehensive testing guide with:
- How to add testing dependency
- Using LocalDurableTestRunner
- Writing test cases
- Best practices
- Examples

## Benefits

1. **Smaller Production Deployments** - ~10% size reduction
2. **Clear Separation** - Production vs. testing code
3. **Extensibility** - Easy to add more testing utilities
4. **Reusability** - Other projects can use testing utilities
5. **No Breaking Changes** - Same package names and APIs

## Risks and Mitigations

### Risk 1: Circular Dependencies

**Risk:** SDK tests need testing module, testing module needs SDK.

**Mitigation:** Testing module depends on SDK (compile), SDK depends on testing module (test scope). Maven handles this correctly.

### Risk 2: Version Mismatch

**Risk:** SDK and testing module versions get out of sync.

**Mitigation:** Use `${project.version}` in parent POM to keep versions synchronized.

### Risk 3: Discovery

**Risk:** Users don't know about testing module.

**Mitigation:** 
- Document prominently in README
- Include in examples
- Add to getting started guide

## Conclusion

Extracting testing utilities into a separate `sdk-testing` module provides clear benefits:
- Smaller production deployments
- Better separation of concerns
- Extensible testing framework
- No breaking changes

**Recommendation:** Proceed with implementation.
