# Deployment Guide

## Prerequisites

- AWS CLI configured with credentials
- AWS SAM CLI installed
- Java 17
- Maven
- Docker (for local testing)

## Quick Start

1. **Build the project:**
   ```bash
   mvn clean package
   ```

2. **Deploy with SAM:**
   ```bash
   sam deploy --guided
   ```
   
   Use these settings:
   - Stack Name: `durable-examples`
   - AWS Region: `us-east-2` (durable functions supported)
   - Confirm changes: `Y`
   - Allow SAM CLI IAM role creation: `Y`
   - Save arguments to configuration file: `Y`

3. **Test the function:**
   ```bash
   # Test SimpleStepExample
   aws lambda invoke \
     --function-name simple-step-example \
     --payload '{"name":"Alice"}' \
     --cli-binary-format raw-in-base64-out \
     response.json
   
   cat response.json
   ```
   
   Expected output: `"HELLO, ALICE!"`
   
   ```bash
   # Test WaitExample
   aws lambda invoke \
     --function-name wait-example \
     --payload '{"name":"Bob"}' \
     --cli-binary-format raw-in-base64-out \
     response.json
   
   cat response.json
   ```
   
   Expected output (after waits complete): `"Started processing for Bob - continued after 10s - completed after 5s more"`

## Testing Locally with SAM

### Option 1: Local Lambda Endpoint

Start a local Lambda endpoint:

```bash
sam local start-lambda
```

In another terminal, invoke the function:

```bash
# Invoke SimpleStepExample
aws lambda invoke \
  --function-name SimpleStepExampleFunction \
  --endpoint-url http://127.0.0.1:3001 \
  --payload '{"name":"Alice"}' \
  --cli-binary-format raw-in-base64-out \
  response.json

# Invoke WaitExample
aws lambda invoke \
  --function-name WaitExampleFunction \
  --endpoint-url http://127.0.0.1:3001 \
  --payload '{"name":"Bob"}' \
  --cli-binary-format raw-in-base64-out \
  response.json
```

### Option 2: Direct Invocation

Invoke the function directly:

```bash
sam local invoke SimpleStepExampleFunction \
  --event event.json
```

Create `event.json`:
```json
{
  "name": "Alice"
}
```

### Option 3: Unit Tests (Recommended)

Run unit tests without Docker:

```bash
mvn test
```

This uses `LocalDurableTestRunner` for fast, local testing.

## Subsequent Deployments

After the first deployment, simply run:

```bash
mvn clean package
sam deploy
```

## Cleanup

Remove all resources:

```bash
sam delete
```

## Troubleshooting

### Function not found
Ensure you're in the correct region:
```bash
aws lambda list-functions --region us-east-2
```

### Permission errors
The SAM template automatically creates the required IAM policies for:
- `lambda:CheckpointDurableExecutions`
- `lambda:GetDurableExecutionState`

### Build errors
Ensure the SDK is installed locally:
```bash
cd .. && mvn clean install -DskipTests
cd examples && mvn clean package
```

### Docker errors (local testing)
Ensure Docker is running:
```bash
docker ps
```

## Workflow Summary

```bash
# 1. Local development and testing
mvn test

# 2. Build deployment package
mvn clean package

# 3. Test locally with SAM (optional)
sam local invoke SimpleStepExampleFunction --event event.json

# 4. Deploy to AWS
sam deploy

# 5. Test in AWS
aws lambda invoke \
  --function-name simple-step-example \
  --payload '{"name":"Test"}' \
  --cli-binary-format raw-in-base64-out \
  response.json
```
