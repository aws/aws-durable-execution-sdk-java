## invoke() - Invoke Other Lambda Functions Durably

The invoke operation calls another Lambda function and waits for its result. It supports both durable functions and standard on-demand Lambda functions.

When you call `ctx.invoke()` or `ctx.invokeAsync()`, the SDK checkpoints the start of the operation and the durable functions backend invokes the target Lambda function. On replay, the SDK returns the checkpointed result without invoking the target again.

`functionName` can be a Lambda function name or ARN. Durable function targets require an alias or version qualifier; standard on-demand Lambda functions do not require a qualifier.


```java
// Basic invoke
var result = ctx.invoke("invoke-function", 
				"function-name",
				"\"payload\"",
				Result.class, 
				InvokeConfig.builder()
						.payloadSerDes(...)  // payload serializer
						.serDes(...)         // result deserializer
						.usePersistedSerDesForPayload(true) // compatible durable targets; currently Java only
						.tenantId(...)       // Lambda tenantId
						.build()
		);
				
```

Invoke payloads use the caller's context-free input codec by default and are sent without SDK framing. This preserves
compatibility with standard Lambda functions, non-Java durable functions, and older Java SDK versions. Enable
`usePersistedSerDesForPayload(true)` only when the target is a compatible durable handler with the same persisted
SerDes pipeline and an implementation of the framed-input protocol. Java durable handlers can opt in with
`DurableConfig.Builder.withPersistedSerDesForChainedInvokePayloads(true)`; other language SDKs do not currently
implement the protocol. Target acceptance is disabled by default, so payload bytes cannot select the persisted
pipeline without callee opt-in. The contract is specified in
[Persisted SerDes wire formats](../wire-formats/persisted-serdes.md).
