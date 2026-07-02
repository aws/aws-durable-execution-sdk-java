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
						.tenantId(...)       // Lambda tenantId
						.build()
		);
				
```
