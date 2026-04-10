## map() – Data-Driven Concurrent Execution

`map()` applies a function to each item in a collection concurrently, with each item running in its own child context. Results are collected into a `MapResult<T>` that maintains input order.

```java
// Basic map: process items concurrently
var items = List.of("order-1", "order-2", "order-3");
var result = ctx.map("process-orders", items, OrderResult.class, (orderId, index, childCtx) -> {
    return childCtx.step("fetch-" + index, OrderResult.class, 
        stepCtx -> orderService.process(orderId));
});

// Results maintain input order
OrderResult first = result.getResult(0);   // result for "order-1"
OrderResult second = result.getResult(1);  // result for "order-2"
assertTrue(result.allSucceeded());
```

Each item's function receives its own `DurableContext`, so you can use any durable operation (`step()`, `wait()`, `invoke()`, etc.) inside the map function.

### mapAsync() – Non-Blocking Map

`mapAsync()` starts the map operation without blocking, returning a `DurableFuture<MapResult<T>>`:

```java
var future = ctx.mapAsync("process-orders", items, OrderResult.class, (orderId, index, childCtx) -> {
    return childCtx.step("process-" + index, OrderResult.class, stepCtx -> process(orderId));
});

// Do other work while map runs...
var otherResult = ctx.step("other-work", String.class, stepCtx -> doOtherWork());

// Block when you need the results
MapResult<OrderResult> result = future.get();
```

### MapResult

`MapResult<T>` holds ordered results from the map operation. Each item is represented as a `MapResultItem<T>` containing its status, result, and error:

| Method | Description |
|--------|-------------|
| `getResult(i)` | Result at index `i`, or `null` if that item failed |
| `getError(i)` | `MapError` at index `i`, or `null` if that item succeeded |
| `getItem(i)` | The `MapResultItem` at index `i` with status, result, and error |
| `allSucceeded()` | `true` if every item succeeded |
| `size()` | Number of items in the result |
| `items()` | All result items as an unmodifiable list |
| `results()` | All results as an unmodifiable list (nulls for failed items) |
| `succeeded()` | Only the non-null (successful) results |
| `failed()` | Only the non-null `MapError`s |
| `completionReason()` | Why the operation completed (`ALL_COMPLETED`, `MIN_SUCCESSFUL_REACHED`, `FAILURE_TOLERANCE_EXCEEDED`) |

### MapResultItem

Each `MapResultItem<T>` contains:

| Field | Description |
|-------|-------------|
| `status()` | `SUCCEEDED`, `FAILED`, or `SKIPPED` |
| `result()` | The result value, or `null` if failed/skipped |
| `error()` | The error details as `MapError`, or `null` if succeeded/skipped |

### MapError

Failed items store error details as `MapError`, a serializable record that survives checkpoint-and-replay cycles:

| Field | Description |
|-------|-------------|
| `errorType()` | Fully qualified exception class name (e.g., `java.lang.RuntimeException`) |
| `errorMessage()` | The exception message |
| `stackTrace()` | Stack trace frames as a list of strings, or `null` |

### Error Isolation

One item's failure does not prevent other items from completing. Failed items are captured in the `MapResult` at their corresponding index:

```java
var result = ctx.map("risky-work", items, String.class, (item, index, childCtx) -> {
    if (item.equals("bad")) throw new RuntimeException("failed");
    return item.toUpperCase();
});

assertFalse(result.allSucceeded());
assertNotNull(result.getError(1));    // the failed item
assertEquals("A", result.getResult(0)); // other items still succeed
```

### MapConfig

Configure concurrency limits and completion criteria with `MapConfig`:

```java
var config = MapConfig.builder()
    .maxConcurrency(5)                                    // at most 5 items run at once
    .completionConfig(CompletionConfig.allCompleted())    // default: run all items
    .build();

var result = ctx.map("process-orders", items, OrderResult.class, 
    (orderId, index, childCtx) -> process(orderId, childCtx), config);
```

`MapConfig` also supports a custom `serDes` for serialization via `.serDes(customSerDes)`. By default, the context's serializer is used. `maxConcurrency` must be at least 1 if set.

#### Concurrency Limiting

`maxConcurrency` controls how many items execute concurrently. When set, items beyond the limit are queued and started as earlier items complete. Default is `null` (unlimited).

```java
// Sequential execution: one item at a time
var sequential = MapConfig.builder().maxConcurrency(1).build();

// Limited concurrency
var limited = MapConfig.builder().maxConcurrency(3).build();
```

#### CompletionConfig

`CompletionConfig` controls when the map operation stops starting new items:

| Factory Method | Behavior |
|----------------|----------|
| `allCompleted()` (default) | All items run regardless of failures |
| `allSuccessful()` | Stop if any item fails (zero failures tolerated) |
| `firstSuccessful()` | Stop after the first item succeeds |
| `minSuccessful(n)` | Stop after `n` items succeed |
| `toleratedFailureCount(n)` | Stop after more than `n` failures |
| `toleratedFailurePercentage(p)` | Stop when failure rate exceeds `p` (0.0–1.0) |

```java
// Stop after 2 successes
var config = MapConfig.builder()
    .maxConcurrency(1)
    .completionConfig(CompletionConfig.minSuccessful(2))
    .build();

var result = ctx.map("find-two", items, String.class, fn, config);
assertEquals(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED, result.completionReason());
```

When early termination triggers, items that were never started have `SKIPPED` status with `null` for both result and error in the `MapResult`.

### Checkpoint-and-Replay

Map operations are fully durable. On replay after interruption:

- Completed items return cached results without re-execution
- Incomplete items resume from their last checkpoint
- Items that never started execute fresh

Small results (< 256KB) are checkpointed directly. Large results are reconstructed from individual child context checkpoints on replay.

### Input Collection Requirements

The input collection must have deterministic iteration order. `List`, `LinkedList`, and `TreeSet` are accepted. `HashSet` and unordered map views are rejected with `IllegalArgumentException`.

```java
// OK
ctx.map("work", List.of("a", "b"), String.class, fn);
ctx.map("work", new ArrayList<>(items), String.class, fn);

// Throws IllegalArgumentException
ctx.map("work", new HashSet<>(items), String.class, fn);
```

### MapFunction Interface

The function passed to `map()` is a `MapFunction<I, O>`:

```java
@FunctionalInterface
public interface MapFunction<I, O> {
    O apply(I item, int index, DurableContext context);
}
```

The `index` parameter is the zero-based position of the item in the input collection, useful for naming operations or correlating results.
