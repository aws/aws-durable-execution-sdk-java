// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

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
 *     () -> fetchItems());
 * }</pre>
 *
 * @param <T> the type being captured
 */
public abstract class TypeToken<T> {
    // cache for types
    private static final Map<Type, Type> TYPE_CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private final Type type;

    /**
     * Constructs a new TypeToken. This constructor must be called from an anonymous subclass to capture the type
     * parameter.
     *
     * @throws IllegalStateException if created without a type parameter
     */
    protected TypeToken() {
        this.type = TYPE_CACHE.computeIfAbsent(getClass(), k -> resolveType(getClass()));
    }

    private static Type resolveType(Class<?> aClass) {
        Type superClass = aClass.getGenericSuperclass();
        if (superClass instanceof ParameterizedType) {
            return ((ParameterizedType) superClass).getActualTypeArguments()[0];
        } else {
            throw new IllegalStateException("TypeToken must be created as an anonymous subclass with a type parameter. "
                    + "Example: new TypeToken<List<String>>() {}");
        }
    }

    private TypeToken(Type type) {
        this.type = type;
    }

    public static <U> TypeToken<U> get(Class<U> clazz) {
        return new TypeToken<>(clazz) {};
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
        if (this == obj) return true;
        if (!(obj instanceof TypeToken)) return false;
        TypeToken<?> other = (TypeToken<?>) obj;
        return type.equals(other.type);
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
