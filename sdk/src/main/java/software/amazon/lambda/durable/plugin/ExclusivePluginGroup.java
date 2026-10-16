// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares mutually exclusive instrumentation without adding methods to an existing plugin contract. Only one plugin
 * instance in a group may be configured, including when multiple instances have the same concrete class. Subclasses
 * retain every group declared by their superclasses, even when they declare another group. Environment discovery reuses
 * an already-explicit implementation of the exact concrete type instead of constructing a duplicate. Older cores ignore
 * this optional metadata and retain their existing registration behavior.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExclusivePluginGroup {
    String value();
}
