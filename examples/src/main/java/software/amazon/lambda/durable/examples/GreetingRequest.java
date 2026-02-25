// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

public class GreetingRequest {
    private String name;

    public GreetingRequest() {}

    public GreetingRequest(String name) {
        this.name = name;
    }

    public String getName() {
        return name != null ? name : "World";
    }

    public void setName(String name) {
        this.name = name;
    }
}
