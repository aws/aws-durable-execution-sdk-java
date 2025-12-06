package com.amazonaws.lambda.durable;

import java.util.concurrent.*;
import java.util.function.Function;

public class DurableFuture<T> implements Future<T> {
    private final CompletableFuture<T> delegate;
    
    DurableFuture(CompletableFuture<T> delegate) {
        this.delegate = delegate;
    }
    
    public T join() {
        return delegate.join();
    }
    
    @Override
    public T get() throws InterruptedException, ExecutionException {
        return delegate.get();
    }
    
    @Override
    public T get(long timeout, TimeUnit unit) 
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.get(timeout, unit);
    }
    
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return delegate.cancel(mayInterruptIfRunning);
    }
    
    @Override
    public boolean isCancelled() {
        return delegate.isCancelled();
    }
    
    @Override
    public boolean isDone() {
        return delegate.isDone();
    }
    
    public <U> DurableFuture<U> thenCompose(Function<T, DurableFuture<U>> fn) {
        return new DurableFuture<>(
            delegate.thenCompose(t -> fn.apply(t).delegate)
        );
    }
    
    CompletableFuture<T> toCompletableFuture() {
        return delegate;
    }
}
