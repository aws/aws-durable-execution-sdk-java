// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.common.AttributeKey;

/**
 * OTel span attribute keys for durable execution spans.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
final class SpanAttributes {

    private SpanAttributes() {}

    static final AttributeKey<String> DURABLE_EXECUTION_ARN = AttributeKey.stringKey("durable.execution.arn");
    static final AttributeKey<String> DURABLE_OPERATION_ID = AttributeKey.stringKey("durable.operation.id");
    static final AttributeKey<String> DURABLE_OPERATION_TYPE = AttributeKey.stringKey("durable.operation.type");
    static final AttributeKey<String> DURABLE_OPERATION_NAME = AttributeKey.stringKey("durable.operation.name");
    static final AttributeKey<String> DURABLE_OPERATION_SUBTYPE = AttributeKey.stringKey("durable.operation.subtype");
    static final AttributeKey<String> DURABLE_OPERATION_PARENT_ID =
            AttributeKey.stringKey("durable.operation.parent_id");
    static final AttributeKey<Long> DURABLE_ATTEMPT_NUMBER = AttributeKey.longKey("durable.attempt.number");
    static final AttributeKey<String> DURABLE_ATTEMPT_OUTCOME = AttributeKey.stringKey("durable.attempt.outcome");
    static final AttributeKey<String> DURABLE_INVOCATION_STATUS = AttributeKey.stringKey("durable.invocation.status");
    static final AttributeKey<Boolean> DURABLE_FIRST_INVOCATION = AttributeKey.booleanKey("durable.invocation.first");
}
