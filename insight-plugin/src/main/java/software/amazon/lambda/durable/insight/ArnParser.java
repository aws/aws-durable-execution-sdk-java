// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

/**
 * Parses the durable execution ARN into its component fields, mirroring the JS {@code parseExecutionArn}.
 *
 * <p>Format:
 * {@code arn:<partition>:lambda:<region>:<accountId>:function:<functionName>:<qualifier>/durable-execution/<executionName>/<invocationId>}.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class ArnParser {
    private final String functionName;
    private final String qualifier;
    private final String region;
    private final String accountId;
    private final String executionName;

    private ArnParser(String functionName, String qualifier, String region, String accountId, String executionName) {
        this.functionName = functionName;
        this.qualifier = qualifier;
        this.region = region;
        this.accountId = accountId;
        this.executionName = executionName;
    }

    public static ArnParser parse(String executionArn) {
        String[] parts = executionArn.split(":", -1);
        String lastPart = parts.length > 7 ? parts[7] : "";
        String[] segments = lastPart.split("/", -1);
        return new ArnParser(
                parts.length > 6 ? parts[6] : "",
                segments.length > 0 ? segments[0] : "",
                parts.length > 3 ? parts[3] : "",
                parts.length > 4 ? parts[4] : "",
                segments.length > 2 ? segments[2] : "");
    }

    public String functionName() {
        return functionName;
    }

    public String qualifier() {
        return qualifier;
    }

    public String region() {
        return region;
    }

    public String accountId() {
        return accountId;
    }

    public String executionName() {
        return executionName;
    }
}
