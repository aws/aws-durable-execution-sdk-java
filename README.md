# AWS Lambda Durable Execution SDK for Java

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/java-%3E%3D17-brightgreen)](https://openjdk.org/)

Build resilient, long-running AWS Lambda functions that automatically checkpoint progress and resume after failures. Durable functions can run for up to one year while you pay only for active compute time.

## Key Features

- **Automatic Checkpointing** – Progress is saved after each step; failures resume from the last checkpoint
- **Cost-Effective Waits** – Suspend execution for minutes, hours, or days without compute charges
- **Configurable Retries** – Built-in retry strategies with exponential backoff and jitter
- **Replay Safety** – Functions deterministically resume from checkpoints after interruptions
- **Type Safety** – Full generic type support for step results

## How It Works

Your durable function extends `DurableHandler<I, O>` and implements `handleRequest(I input, DurableContext ctx)`. The `DurableContext` is your interface to durable operations:

- `ctx.step()` – Execute code and checkpoint the result
- `ctx.wait()` – Suspend execution without compute charges
- `ctx.createCallback()` – Wait for external events (approvals, webhooks)
- `ctx.waitForCallback()` – Simplify callback handling by combining callback creation and submission in one operation
- `ctx.invoke()` – Invoke another Lambda function and wait for the result
- `ctx.runInChildContext()` – Run an isolated child context with its own checkpoint log

## Quick Start

### Installation

```xml
<dependency>
    <groupId>software.amazon.lambda.durable</groupId>
    <artifactId>aws-durable-execution-sdk-java</artifactId>
    <version>VERSION</version>
</dependency>
```

### Your First Durable Function

```java
public class OrderProcessor extends DurableHandler<Order, OrderResult> {

    @Override
    protected OrderResult handleRequest(Order order, DurableContext ctx) {
        // Step 1: Validate and reserve inventory
        var reservation = ctx.step("reserve-inventory", Reservation.class, 
            () -> inventoryService.reserve(order.getItems()));

        // Step 2: Process payment
        var payment = ctx.step("process-payment", Payment.class,
            () -> paymentService.charge(order.getPaymentMethod(), order.getTotal()));

        // Wait for warehouse processing (no compute charges)
        ctx.wait(null, Duration.ofHours(2));

        // Step 3: Confirm shipment
        var shipment = ctx.step("confirm-shipment", Shipment.class,
            () -> shippingService.ship(reservation, order.getAddress()));

        return new OrderResult(order.getId(), shipment.getTrackingNumber());
    }
}
```

## Deployment

See [examples/README.md](./examples/README.md) for complete instructions on local testing and running cloud integration tests.

See [Deploy and invoke Lambda durable functions with the AWS CLI](https://docs.aws.amazon.com/lambda/latest/dg/durable-getting-started-cli.html) for more information on deploying and invoking durable functions.

See [Deploy Lambda durable functions with Infrastructure as Code](https://docs.aws.amazon.com/lambda/latest/dg/durable-getting-started-iac.html) for more information on deploying durable functions using infrastructure-as-code.

## Documentation

- [<u>AWS Documentation</u>](https://docs.aws.amazon.com/lambda/latest/dg/durable-functions.html) – Official AWS Lambda durable functions guide
- [<u>Best Practices</u>](https://docs.aws.amazon.com/lambda/latest/dg/durable-best-practices.html) – Patterns and recommendations
- [<u>SDK Design</u>](docs/design.md) – Details of SDK internal architecture

**Core Operations**

- [<u>Steps</u>](docs/core/steps.md) – Execute code with automatic checkpointing and retry support
- [<u>Wait</u>](docs/core/wait.md) - Pause execution without blocking Lambda resources
- [<u>Callbacks</u>](docs/core/callbacks.md) - Wait for external systems to respond
- [<u>Invoke</u>](docs/core/invoke.md) - Call other durable functions
- [<u>Child Contexts</u>](docs/core/child-contexts.md) - Organize complex workflows into isolated units

**Examples**

- [<u>Examples</u>](examples/README.md) - Working examples of each operation

**Advanced Topics**

- [<u>Configuration</u>](docs/advanced/configuration.md) - Customize SDK behaviour
- [<u>Error Handling</u>](docs/advanced/error-handling.md) - SDK exceptions for handling failures
- [<u>Logging</u>](docs/advanced/logging.md) - How to use DurableLogger
- [<u>Testing</u>](docs/advanced/testing.md) - Utilities for local development and cloud-based integration testing

## Related SDKs

- [JavaScript/TypeScript SDK](https://github.com/aws/aws-durable-execution-sdk-js)
- [Python SDK](https://github.com/aws/aws-durable-execution-sdk-python)

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for information about reporting security issues.

## License

This project is licensed under the Apache-2.0 License. See [LICENSE](LICENSE) for details.
