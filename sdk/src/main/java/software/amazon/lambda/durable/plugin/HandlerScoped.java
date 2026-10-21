// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Function;

/**
 * Explicitly opts a plugin into a root-handler scope using a JDK {@link Function} opener with a public no-argument
 * constructor. The core passes the plugin instance to that opener on the handler thread; null means no scope. No method
 * name is discovered on the plugin or added to the existing lifecycle interface. Inherited metadata uses the declaring
 * plugin's explicit opener, so unrelated methods on old subclasses retain their behavior. Older cores ignore this
 * optional metadata; plugin layer signatures require no new shared SDK type.
 *
 * <p>Scopes open on the handler thread and close on that same thread in reverse order. The invocation waits for all
 * scope cleanup before dispatching invocation-end hooks, including during suspension or termination. No cleanup
 * deadline or reserved time budget is enforced: a blocking close can delay invocation-end hooks and caller return
 * indefinitely. Ordinary cleanup preserves the winning outcome; observed VirtualMachineError/ThreadDeath from scope
 * callbacks escapes the invocation caller after cleanup completes.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface HandlerScoped {
    Class<? extends Function<?, ? extends AutoCloseable>> value();
}
