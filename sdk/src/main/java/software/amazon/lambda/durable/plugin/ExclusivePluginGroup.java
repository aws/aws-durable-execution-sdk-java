// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares mutually exclusive instrumentation without adding methods to an existing plugin contract. At most one plugin
 * in a group may be configured. Subclasses inherit their instrumentation group. Older cores ignore this optional
 * metadata and retain their existing registration behavior.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExclusivePluginGroup {
    String value();
}
