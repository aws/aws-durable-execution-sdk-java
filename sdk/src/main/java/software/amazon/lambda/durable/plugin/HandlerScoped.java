// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicitly opts a plugin into a scope around the root handler, after invocation startup. Annotated classes expose a
 * public, no-argument {@code AutoCloseable openHandlerScope()} method; returning null means no scope. The method must
 * be accessible to the core (including module access). The annotation is inherited so concrete subclass overrides keep
 * their ordinary virtual dispatch. Unannotated, coincidentally named application methods are never called by the SDK.
 * Older cores ignore this optional metadata and retain their original hook behavior; plugin method signatures use only
 * JDK types.
 *
 * <p>Scopes open and close on the handler thread, in reverse order on exit. Suspension/termination gives cleanup a
 * bounded opportunity to unwind. The handler retains ownership even after a timeout. Ordinary cleanup preserves the
 * winning outcome; observed VirtualMachineError/ThreadDeath from scope callbacks escapes the invocation caller.
 * Optional waiting reserves five seconds per configured plugin plus one second for shutdown/response. This is best
 * effort: existing finalizers and checkpoint draining can exceed it.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface HandlerScoped {}
