// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.lmi;

import com.amazonaws.services.lambda.runtime.Context;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.serde.JacksonSerDes;

/** Captures the fixture's actual structured diagnostics; never used by the cloud handler. */
final class TraceCapture implements AutoCloseable {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream previous = System.out;
    private final PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    TraceCapture() {
        System.setOut(output);
    }

    static InvocationTrace trace(String requestId) {
        var context = (Context) Proxy.newProxyInstance(
                Context.class.getClassLoader(),
                new Class<?>[] {Context.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAwsRequestId" -> requestId;
                    case "getRemainingTimeInMillis" -> 60_000;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new InvocationTrace(
                new FixtureInput("run", "cohort", "nested", requestId, null, null, 2, 1000),
                context,
                "execution:" + requestId);
    }

    List<Map<String, Object>> events() {
        var json = new JacksonSerDes();
        return bytes.toString(StandardCharsets.UTF_8)
                .lines()
                .filter(line -> line.startsWith("LMI_TEST "))
                .map(line -> json.deserialize(line.substring(9), new TypeToken<Map<String, Object>>() {}))
                .toList();
    }

    @Override
    public void close() {
        System.setOut(previous);
        output.close();
    }
}
