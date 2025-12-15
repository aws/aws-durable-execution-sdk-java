#!/bin/bash
set -e

# Install SDK
cd ..
mvn install -DskipTests

# Package example
cd examples
mvn clean package -DskipTests

# Run SAM local invoke
sam local invoke SimpleStepExampleFunction --event event.json --skip-pull-image
