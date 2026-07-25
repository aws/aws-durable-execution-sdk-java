// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.dag.DagResult;

/**
 * Builds the canonical cross-language DAG summary fields shared by the {@code dag} conformance handlers (see
 * {@code test-requirements/dag/10-*.yaml}): the completion reason, the per-task terminal statuses, and the
 * {@code [succeeded, failed, skipped, total]} counts.
 */
final class DagSummary {

    private DagSummary() {}

    /** Per-task terminal status map (name -&gt; status name), including SKIPPED tasks. */
    static Map<String, Object> statuses(DagResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        r.results().forEach((name, te) -> m.put(name, te.status().name()));
        return m;
    }

    /** The canonical {@code [succeeded, failed, skipped, total]} counts array. */
    static List<Integer> counts(DagResult r) {
        return List.of(r.successCount(), r.failureCount(), r.skippedCount(), r.totalCount());
    }
}
