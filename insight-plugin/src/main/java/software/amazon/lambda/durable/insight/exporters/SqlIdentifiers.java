// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import java.util.regex.Pattern;

/** Validates SQL identifiers (table and schema names) before they are spliced into statement text. */
final class SqlIdentifiers {
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private SqlIdentifiers() {}

    /** Returns the name unchanged, or throws when it contains anything but letters, digits, and underscores. */
    static String validate(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid SQL identifier: \"" + name + "\". Only letters, digits, and underscores are allowed.");
        }
        return name;
    }
}
