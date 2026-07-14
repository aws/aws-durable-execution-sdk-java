// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Metadata used by {@code generate-template.py} when producing the examples SAM template. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface ExampleTemplate {
    String condition() default "";

    boolean tracing() default false;

    boolean adotJavaAgent() default false;
}
