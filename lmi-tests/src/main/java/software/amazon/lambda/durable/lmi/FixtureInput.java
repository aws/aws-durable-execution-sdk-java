// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.lmi;

/** Test controls are deliberately separate from durable operation identity. */
public record FixtureInput(
        String runId,
        String cohort,
        String scenario,
        String marker,
        String controlUrl,
        String targetEnvironment,
        int peers,
        int holdMillis,
        String otelView,
        String otelExport,
        String exportControlUrl) {
    public FixtureInput(
            String runId,
            String cohort,
            String scenario,
            String marker,
            String controlUrl,
            String targetEnvironment,
            int peers,
            int holdMillis) {
        this(runId, cohort, scenario, marker, controlUrl, targetEnvironment, peers, holdMillis, null, null, null);
    }
}
