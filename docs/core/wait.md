## wait() – Suspend Without Cost

Waits suspend the function and resume after the specified duration. You're not charged during suspension.

```java
// Wait 30 minutes
ctx.wait(null, Duration.ofMinutes(30));

// Named wait (useful for debugging)
ctx.wait("cooling-off-period", Duration.ofDays(7));
```

## waitAsync() – Non-Blocking Wait

`waitAsync()` starts the wait timer but returns a `DurableFuture<Void>` immediately, allowing other operations to run concurrently. The execution only suspends when you call `.get()` on the future (if the wait hasn't completed yet).

```java
// Start a 5-second timer (non-blocking)
DurableFuture<Void> timer = ctx.waitAsync("min-delay", Duration.ofSeconds(5));

// Do work while the timer runs
var result = ctx.step("process", String.class, stepCtx -> doWork());

// Block until the wait elapses
timer.get();
```