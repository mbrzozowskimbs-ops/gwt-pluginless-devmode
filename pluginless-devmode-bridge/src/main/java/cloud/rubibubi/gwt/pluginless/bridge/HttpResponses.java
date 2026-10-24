package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;

/**
 * Plain text responses shared by the bridge's HTTP handlers.
 */
final class HttpResponses {

    private HttpResponses() {
    }

    /**
     * Sends a non-cacheable {@code text/plain} response, unless the response has already started.
     *
     * @param exchange exchange to answer
     * @param status   HTTP status
     * @param text     body, or {@code null} for none
     * @throws IOException when writing fails
     */
    static void text(HttpExchange exchange, int status, String text) throws IOException {
        if (exchange.getResponseCode() != -1) {
            // A failure after the response started streaming; the connection is closed by the caller
            return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (text == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
