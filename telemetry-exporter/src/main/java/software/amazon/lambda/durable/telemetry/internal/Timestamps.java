// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Internal timestamp format for Telemetry records and the Lambda log envelope. */
public final class Timestamps {
    /** UTC ISO-8601 with exactly three fraction digits and a literal {@code Z}. */
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Timestamps() {}

    /**
     * Formats {@code instant} as {@code YYYY-MM-DDTHH:MM:SS.sssZ}, the format of JavaScript's
     * {@code Date.prototype.toISOString()}.
     *
     * <p>The fraction always has three digits. A zero fraction is written as {@code .000}. Digits below the millisecond
     * are dropped, not rounded, so {@code .078757096} becomes {@code .078} and {@code .999999999} becomes {@code .999}.
     *
     * <p>Destinations such as DynamoDB and CloudWatch Logs Insights compare these values as text. Text order equals
     * time order only when every value has the same width. {@link Instant#toString()} writes 0, 3, 6 or 9 fraction
     * digits. So every record timestamp goes through this method instead.
     */
    public static String format(Instant instant) {
        return FORMAT.format(instant);
    }
}
