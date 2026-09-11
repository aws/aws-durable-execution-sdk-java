// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Loopback HTTP server that records every request and answers with a configurable status. */
final class LocalHttpServer implements AutoCloseable {

    /** One captured request. */
    static final class Captured {
        final String method;
        final String path;
        final Headers headers;
        final String body;

        Captured(String method, String path, Headers headers, String body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }

    private final HttpServer server;
    final List<Captured> requests = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile long delayMillis = 0;

    LocalHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Captured(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestHeaders(),
                    body));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] reply = "denied".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    Captured only() {
        if (requests.size() != 1) {
            throw new AssertionError("expected exactly one request, got " + requests.size());
        }
        return requests.get(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
