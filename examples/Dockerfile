# Build stage
FROM amazoncorretto:17-alpine AS builder

# Install Maven
RUN apk add --no-cache maven
WORKDIR /build

# Copy parent pom and all modules
COPY pom.xml .
COPY sdk ./sdk
COPY sdk-testing ./sdk-testing
COPY sdk-integration-tests ./sdk-integration-tests
COPY examples ./examples

# Build and install the SDK modules first
RUN mvn clean install -DskipTests -pl sdk,sdk-testing -am

# Build the examples project
RUN mvn clean package -DskipTests -pl examples -am

# Runtime stage
FROM public.ecr.aws/lambda/java:17

# Copy only the built JAR from build stage
COPY --from=builder /build/examples/target/*.jar ${LAMBDA_TASK_ROOT}/lib/

# Default CMD (will be overridden by ImageConfig.Command in SAM template)
CMD ["software.amazon.lambda.durable.examples.SimpleStepExample::handleRequest"]
