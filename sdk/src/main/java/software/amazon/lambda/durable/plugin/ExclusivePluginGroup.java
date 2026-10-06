// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares mutually exclusive instrumentation without adding methods to an existing plugin contract. Different concrete
 * plugin classes in a group cannot be configured together. Subclasses retain every group declared by their
 * superclasses, even when they declare another group. Repeated registrations of the same concrete class remain
 * permitted for compatibility with older clients that copy resolved configurations. Older cores ignore this optional
 * metadata and retain their existing registration behavior.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExclusivePluginGroup {
    String value();
}
