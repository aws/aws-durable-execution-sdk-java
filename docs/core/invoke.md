## invoke() - Invoke another Lambda function


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