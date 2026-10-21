// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class AsyncFailureUnwrappingTest {
    @Test
    void nestedFutureWrappersPreserveTheActualFatalCause() {
        var fatal = new InternalError("fatal");
        var checked = new ExecutionException(fatal);
        var nested = new CompletionException(new ExecutionException(new CompletionException(checked)));
        assertSame(fatal, ExceptionHelper.unwrapAsyncFailure(nested));
        // Existing operation callers retain their completion-only policy and checkpoint error meaning.
        assertSame(checked, ExceptionHelper.unwrapCompletableFuture(new CompletionException(checked)));
    }

    @Test
    void applicationExceptionIsNotReplacedByItsCause() {
        var applicationFailure = new IllegalArgumentException("business error", new InternalError("cause"));
        assertSame(applicationFailure, ExceptionHelper.unwrapAsyncFailure(new CompletionException(applicationFailure)));
    }

    @Test
    void absentCausesRetainDiagnosticWrappers() {
        var completion = new CompletionException((Throwable) null);
        var execution = new ExecutionException((Throwable) null);
        assertSame(completion, ExceptionHelper.unwrapAsyncFailure(completion));
        assertSame(execution, ExceptionHelper.unwrapAsyncFailure(execution));
        assertNull(ExceptionHelper.unwrapAsyncFailure(null));
    }
}
