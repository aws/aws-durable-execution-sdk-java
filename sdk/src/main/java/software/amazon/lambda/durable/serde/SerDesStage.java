// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

/**
 * A reversible string stage in a {@link ComposableSerDes} pipeline.
 *
 * <p>Every top-level stage consumes and produces a string, so stages can be composed in any order without intermediate
 * type mismatches. Use {@link ComposableBinarySerDesStage} to perform an efficient chain of binary transformations
 * inside one string stage.
 *
 * <p>A stage must serialize values into a self-identifying format. During deserialization, it must:
 *
 * <ul>
 *   <li>reverse input that is in its recognized, valid format;
 *   <li>throw a SerDes failure when input identifies itself as this stage's format but is malformed or unsupported;
 *   <li>return input unchanged when it is not in this stage's format.
 * </ul>
 *
 * <p>This pass-through behavior allows raw external payloads to traverse a configured pipeline and reach its root value
 * codec without special pipeline control flow. Implementations should inspect an explicit marker or versioned envelope
 * before decoding rather than treating any successfully decodable value as recognized.
 *
 * <p>The SDK passes the durable payload context explicitly to every stage invocation. The context may be {@code null}
 * only when a pipeline or stage is invoked directly outside an SDK-managed SerDes call.
 */
public interface SerDesStage {
    /**
     * Applies this stage during forward serialization.
     *
     * @param value the non-null input string
     * @param context the current durable payload context, or {@code null} outside SDK-managed calls
     * @return the non-null transformed string
     */
    String serialize(String value, SerDesContext context);

    /**
     * Reverses this stage during deserialization.
     *
     * <p>If {@code data} is not in this stage's self-identifying format, implementations must return it unchanged. If
     * it identifies this stage's format but is malformed or unsupported, implementations must throw a SerDes failure.
     *
     * @param data the non-null input string
     * @param context the current durable payload context, or {@code null} outside SDK-managed calls
     * @return the non-null string expected by the preceding stage, or {@code data} unchanged when unrecognized
     */
    String deserialize(String data, SerDesContext context);
}
