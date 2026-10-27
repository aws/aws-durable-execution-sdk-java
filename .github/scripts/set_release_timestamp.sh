#!/usr/bin/env bash
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

# Override Maven 3.10's 1980 default in every subsequent release build.
timestamp=$(git log -1 --format=%ct)
echo "MAVEN_ARGS=-Dproject.build.outputTimestamp=${timestamp}" >> "$GITHUB_ENV"
