// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Framework-agnostic type token for capturing generic type information at runtime.
 *
 * <p>This class enables type-safe deserialization of complex generic types like {@code List<MyObject>} or
 * {@code Map<String, MyObject>} that would otherwise lose their type information due to Java's type erasure.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * // Capture generic type information
 * TypeToken<List<String>> token = new TypeToken<List<String>>() {};
 *
 * // Use with DurableContext
 * List<String> items = context.step("fetch-items",
 *     new TypeToken<List<String>>() {},
 *     stepCtx -> fetchItems());
 * }</pre>
 *
 * @param <T> the type being captured
 */
public abstract class TypeToken<T> {
    private final Type type;

    /**
     * Constructs a new TypeToken. This constructor must be called from an anonymous subclass to capture the type
     * parameter.
     *
     * @throws IllegalStateException if created without a type parameter
     */
    protected TypeToken() {
        Type superClass = getClass().getGenericSuperclass();
        if (superClass instanceof ParameterizedType parameterizedType) {
            this.type = parameterizedType.getActualTypeArguments()[0];
        } else {
            throw new IllegalStateException("TypeToken must be created as an anonymous subclass with a type parameter. "
                    + "Example: new TypeToken<List<String>>() {}");
        }
    }

    private TypeToken(Type type) {
        this.type = type;
    }

    /**
     * Creates a TypeToken for a simple (non-generic) class.
     *
     * @param clazz the class to create a token for
     * @param <U> the type
     * @return a TypeToken representing the given class
     */
    public static <U> TypeToken<U> get(Class<U> clazz) {
        return new TypeToken<>(clazz) {};
    }

    /**
     * Creates a TypeToken by extracting a type parameter from a generic superclass.
     *
     * @param clazz the subclass to extract the type from
     * @param typeParameterPosition the position of the type parameter in the superclass declaration (0-based)
     * @param <U> the type to extract
     * @param <V> the superclass type
     * @return a TypeToken representing the extracted type
     */
    static <U, V> TypeToken<U> fromGenericSuperClass(Class<V> clazz, int typeParameterPosition) {
        // Extract input type from generic superclass
        var superClass = clazz.getGenericSuperclass();
        if (superClass instanceof ParameterizedType paramType) {
            return new TypeToken<>(paramType.getActualTypeArguments()[typeParameterPosition]) {};
        } else {
            throw new IllegalArgumentException("Cannot determine type from base class: " + clazz);
        }
    }

    /**
     * Returns the captured type.
     *
     * @return the type represented by this token
     */
    public Type getType() {
        return type;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof TypeToken<?> other) {
            return type.equals(other.type);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }

    @Override
    public String toString() {
        return "TypeToken<" + type.getTypeName() + ">";
    }
}
