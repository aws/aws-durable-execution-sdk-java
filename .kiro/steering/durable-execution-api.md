# AWS Lambda Durable Functions Documentation

**Source:** AWS Lambda Developer Guide  
**Last Retrieved:** December 2, 2025

## Overview

AWS Lambda durable functions enable building resilient multi-step applications and AI workflows that can execute for up to one year while maintaining reliable progress despite interruptions. When a durable function runs, this complete lifecycle is called a durable execution, which uses checkpoints to track progress and automatically recover from failures through replay.

### Key Capabilities

- **Extended Execution:** Functions can run for up to one year
- **Automatic State Management:** Built-in checkpointing and state persistence
- **Failure Recovery:** Automatic recovery through replay mechanism
- **Cost Optimization:** No compute charges during wait operations
- **Serverless:** Automatic scaling including scale-to-zero

### Supported Runtimes

- **Node.js:** Versions 22 and 24
- **Python:** Versions 3.13 and 3.14
- **Availability:** US East (Ohio) region at launch

## Core Concepts

### Durable Execution

A durable execution represents the complete lifecycle of a Lambda durable function. It uses a checkpoint and replay mechanism to:
- Track business logic progress
- Suspend execution during long-running tasks
- Recover from failures automatically

The lifecycle may include multiple invocations of a Lambda function, enabling extended execution periods while maintaining reliable progress.

### Replay Mechanism

Lambda maintains a running log of all durable operations (steps, waits, and other operations). When a function needs to pause or encounters an interruption:

1. Lambda saves the checkpoint log and stops execution
2. When resuming, Lambda invokes the function again from the beginning
3. The checkpoint log is replayed, substituting stored values for completed operations
4. Previously completed steps don't re-execute; their stored results are used instead

**Critical Requirement:** Code must be deterministic during replay, producing the same results given the same inputs.

### DurableContext

The context object provided to durable functions, offering methods for durable operations:

**TypeScript Example:**
```typescript
import { DurableContext, withDurableExecution } from "@aws/durable-execution-sdk-js";

export const handler = withDurableExecution(
  async (event: any, context: DurableContext) => {
    const result = await context.step(async () => {
      return "step completed";
    });
    return result;
  }
);
```

**Python Example:**
```python
from aws_durable_execution_sdk_python import DurableContext, durable_execution, durable_step

@durable_step
def my_step(step_context, data):
    # Your business logic
    return result

@durable_execution
def handler(event, context: DurableContext):
    result = context.step(my_step(event["data"]))
    return result
```

### Steps

Steps execute business logic with built-in retries and automatic checkpointing. Each step saves its result, ensuring the function can resume from any completed step after interruptions.

**TypeScript:**
```typescript
const order = await context.step(async () => processOrder(event));
const payment = await context.step(async () => processPayment(order));
const result = await context.step(async () => completeOrder(payment));
```

**Python:**
```python
order = context.step(lambda: process_order(event))
payment = context.step(lambda: process_payment(order))
result = context.step(lambda: complete_order(payment))
```

### Wait States

Planned pauses where the function stops running (and stops charging) until it's time to continue. Use for:
- Time-based delays
- External callbacks
- Specific conditions

**TypeScript:**
```typescript
// Wait for 1 hour without consuming resources
await context.wait({ seconds: 3600 });

// Wait for external callback
const approval = await context.waitForCallback(
  async (callbackId) => sendApprovalRequest(callbackId)
);
```

**Python:**
```python
# Wait for 1 hour without consuming resources
context.wait(3600)

# Wait for external callback
approval = context.wait_for_callback(
    lambda callback_id: send_approval_request(callback_id)
)
```

### Invoking Other Functions

Durable functions can call other Lambda functions and wait for their results:

**TypeScript:**
```typescript
const customerData = await context.invoke(
  'validate-customer',
  'arn:aws:lambda:us-east-1:123456789012:function:customer-service:1',
  { customerId: event.customerId }
);
```

**Python:**
```python
customer_data = context.invoke(
    'arn:aws:lambda:us-east-1:123456789012:function:customer-service:1',
    {'customerId': event['customerId']},
    name='validate-customer'
)
```

**Note:** Cross-account invocations are not supported.

## Configuration

### Execution Timeout

Controls how long a durable execution can run from start to completion (different from Lambda function timeout):

- **Lambda Function Timeout:** Maximum 15 minutes per invocation
- **Durable Execution Timeout:** Maximum 1 year for entire execution
- **Default:** 86,400 seconds (24 hours)
- **Minimum:** 60 seconds
- **Maximum:** 31,536,000 seconds (1 year)

### Retention Period

Controls how long Lambda retains execution history and checkpoint data after completion. This includes step results, execution state, and wait information.

### Required IAM Permissions

Durable functions require additional IAM permissions:
- `lambda:CheckpointDurableExecutions`
- `lambda:GetDurableExecutionState`

These permissions should be scoped to the specific function ARN.

## Best Practices

### 1. Write Deterministic Code

Wrap non-deterministic operations in steps:
- Random number generation and UUIDs
- Current time or timestamps
- External API calls and database queries
- File system operations

**Example:**
```typescript
// Generate transaction ID inside a step
const transactionId = await context.step('generate-transaction-id', async () => {
  return randomUUID();
});
```

**Important:** Don't use global variables or closures to share state between steps. Pass data through return values.

### 2. Design for Idempotency

Operations may execute multiple times due to retries or replay. Use:
- Idempotency tokens for external API calls
- At-most-once semantics for critical operations
- Check-before-write patterns for database operations

**Example:**
```typescript
const idempotencyToken = await context.step('generate-idempotency-token', async () => {
  return crypto.randomUUID();
});

const charge = await context.step('charge-payment', async () => {
  return paymentService.charge({
    amount: event.amount,
    cardToken: event.cardToken,
    idempotencyKey: idempotencyToken
  });
});
```

### 3. Manage State Efficiently

Every checkpoint saves state to persistent storage. Keep state minimal:
- Store IDs and references, not full objects
- Fetch detailed data within steps as needed
- Use Amazon S3 or DynamoDB for large data
- Avoid passing large payloads between steps

**Example:**
```typescript
// Store only the order ID, not the full order object
const orderId = event.orderId;

// Fetch data within each step as needed
await context.step('validate-order', async () => {
  const order = await orderService.getOrder(orderId);
  return validateOrder(order);
});
```

### 4. Design Effective Steps

- Use descriptive names for steps
- Balance granularity (not too fine, not too coarse)
- Group related operations
- Use wait operations efficiently

## Use Cases

### Short-lived Coordination
Coordinate payments, inventory, and shipping across multiple services with automatic rollback on failures.

### Process Payments
Build resilient payment flows that maintain transaction state through failures and handle retries automatically.

### AI Workflows
Create multi-step AI workflows that chain model calls, incorporate human feedback, and handle long-running tasks deterministically.

### Order Fulfillment
Coordinate order processing across inventory, payment, shipping, and notification systems with built-in resilience.

### Business Workflows
Build reliable workflows for employee onboarding, loan approvals, and compliance processes that span days or weeks.

## Deployment

### Infrastructure as Code

Durable functions can be deployed using:
- AWS CloudFormation
- AWS CDK (TypeScript, Python)
- AWS SAM

### Common Configuration Patterns

1. Enable durable execution: Set `DurableExecution.Enabled` to `true`
2. Grant checkpoint permissions
3. Use qualified ARNs (with version or alias)
4. Package dependencies (include SDK in deployment)
5. Configure appropriate logging

### Example CloudFormation Template

```yaml
Resources:
  DurableFunction:
    Type: AWS::Lambda::Function
    Properties:
      FunctionName: my-durable-function
      Runtime: python3.14
      Handler: index.handler
      Code:
        ZipFile: |
          # Function code here
      DurableConfig:
        Enabled: true
        ExecutionTimeout: 86400
        RetentionPeriodInDays: 7
```

## Monitoring

- Use Amazon EventBridge to monitor durable function executions
- View checkpoint operations in AWS CloudTrail data events
- Review CloudWatch Logs for function output and replay behavior
- Monitor execution progress through Lambda console

## SDK Resources

- **JavaScript/TypeScript SDK:** https://github.com/aws/aws-durable-execution-sdk-js
- **Python SDK:** https://github.com/aws/aws-durable-execution-sdk-python

Both SDKs are open source.

## Comparison with Other AWS Services

### Lambda Durable Functions
- Code-first approach within Lambda
- Ideal for developer-centric workflows
- Complex business logic in code
- Rapid prototyping

### AWS Step Functions
- Visual workflow orchestration
- Coordinates multiple Lambda functions and AWS services
- Standard workflows (long-running, auditable)
- Express workflows (high-event-rate, short-duration)

### Amazon EventBridge
- Event bus service for routing events
- EventBridge Scheduler for scheduling Lambda invocations
- Event-driven architectures

## Pricing

- Pay only for actual processing time
- No compute charges during wait operations
- Checkpoint storage costs apply
- See AWS Lambda pricing page for details

## Getting Started

1. Create a Lambda function with Node.js 22/24 or Python 3.13/3.14
2. Enable durable execution during function creation
3. Install the durable execution SDK
4. Wrap your handler with `withDurableExecution` (TypeScript) or `@durable_execution` (Python)
5. Use `context.step()` for business logic
6. Use `context.wait()` for pauses
7. Test with qualified ARN (version or alias)

## Additional Resources

- AWS Lambda Developer Guide: https://docs.aws.amazon.com/lambda/latest/dg/
- Launch Blog Post: https://aws.amazon.com/blogs/aws/build-multi-step-applications-and-ai-workflows-with-aws-lambda-durable-functions/
- AWS Lambda Pricing: https://aws.amazon.com/lambda/pricing/
