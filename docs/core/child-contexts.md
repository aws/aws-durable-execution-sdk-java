## runInChildContext() – Isolated Execution Contexts

Child contexts run an isolated stream of work with their own operation counter and checkpoint log. They support the full range of durable operations — `step`, `wait`, `invoke`, `createCallback`, and nested child contexts.

```java
// Sync: blocks until the child context completes
var result = ctx.runInChildContext("validate-order", String.class, child -> {
    var data = child.step("fetch", String.class, stepCtx -> fetchData());
    child.wait(null, Duration.ofMinutes(5));
    return child.step("validate", String.class, stepCtx -> validate(data));
});

// Async: returns a DurableFuture for concurrent execution
var futureA = ctx.runInChildContextAsync("branch-a", String.class, child -> {
    return child.step("work-a", String.class, stepCtx -> doWorkA());
});
var futureB = ctx.runInChildContextAsync("branch-b", String.class, child -> {
    return child.step("work-b", String.class, stepCtx -> doWorkB());
});

// Wait for all child contexts to complete
var results = DurableFuture.allOf(futureA, futureB);
```