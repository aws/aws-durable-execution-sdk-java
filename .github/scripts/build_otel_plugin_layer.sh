#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <output-zip> <sdk-version> <source-date-epoch>" >&2
  exit 1
fi

OUTPUT_ZIP=$1
SDK_VERSION=$2
SOURCE_DATE_EPOCH=$3

if [ -z "$SDK_VERSION" ]; then
  echo "SDK version must not be empty." >&2
  exit 1
fi
if [[ ! "$SOURCE_DATE_EPOCH" =~ ^[0-9]+$ ]]; then
  echo "Source date epoch must be a non-negative integer." >&2
  exit 1
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
if [[ "$OUTPUT_ZIP" != /* ]]; then
  OUTPUT_ZIP="$(pwd)/$OUTPUT_ZIP"
fi

TEMP_ROOT=${RUNNER_TEMP:-${TMPDIR:-/tmp}}
LAYER_DIR=$(mktemp -d "$TEMP_ROOT/otel-plugin-layer.XXXXXX")
trap 'rm -rf "$LAYER_DIR"' EXIT

mvn -B -q \
  --file "$REPO_ROOT/pom.xml" \
  --projects otel-plugin \
  --also-make \
  clean package \
  -DskipTests \
  -Dproject.build.outputTimestamp="$SOURCE_DATE_EPOCH" \
  --no-transfer-progress

PLUGIN_JAR="$REPO_ROOT/otel-plugin/target/aws-durable-execution-sdk-java-plugin-otel-${SDK_VERSION}.jar"
if [ ! -f "$PLUGIN_JAR" ]; then
  echo "Expected OTel plugin JAR was not built: $PLUGIN_JAR" >&2
  exit 1
fi

mkdir -p "$LAYER_DIR/java/lib" "$(dirname "$OUTPUT_ZIP")"
cp "$PLUGIN_JAR" "$LAYER_DIR/java/lib/"
cp "$REPO_ROOT/LICENSE" "$REPO_ROOT/NOTICE" "$LAYER_DIR/"
find "$LAYER_DIR" -exec touch -d "@${SOURCE_DATE_EPOCH}" {} +

rm -f "$OUTPUT_ZIP"
(
  cd "$LAYER_DIR"
  find . -type f -print | LC_ALL=C sort | zip -X -q "$OUTPUT_ZIP" -@
)
