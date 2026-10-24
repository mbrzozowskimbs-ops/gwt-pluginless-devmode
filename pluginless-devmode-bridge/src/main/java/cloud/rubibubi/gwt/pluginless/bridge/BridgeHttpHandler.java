package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * HTTP endpoint of the bridge.
 * <ul>
 *     <li>{@code POST /pluginless-devmode/session/<id>} — a JSON array of browser messages, answered with a JSON array of
 *     commands (see {@code docs/PROTOCOL.md}),</li>
 *     <li>{@code OPTIONS /pluginless-devmode/session/<id>} — CORS preflight,</li>
 *     <li>{@code GET /pluginless-devmode/health} — liveness check returning {@code ok}.</li>
 * </ul>
 * Bodies are sent as {@code text/plain} so that the browser's synchronous cross-origin XHR needs no preflight.
 * Failures of a session are reported to the browser as an {@code error} command with HTTP 200, so the page can
 * show them; HTTP errors are used only for requests that must not reach a session.
 */
final class BridgeHttpHandler implements HttpHandler {

    static final String CONTEXT = "/pluginless-devmode/";
    private static final String SESSION_PREFIX = CONTEXT + "session/";
    private static final String HEALTH_PATH = CONTEXT + "health";

    private static final System.Logger LOG = System.getLogger(BridgeHttpHandler.class.getName());

    private final SessionRegistry sessions;
    private final OriginPolicy originPolicy;

    BridgeHttpHandler(SessionRegistry sessions, OriginPolicy originPolicy) {
        this.sessions = sessions;
        this.originPolicy = originPolicy;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Unexpected bridge failure", e);
            HttpResponses.text(exchange, 500, "Internal bridge error: " + e);
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getRequestHeaders();
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if (HEALTH_PATH.equals(path) && "GET".equals(method)) {
            HttpResponses.text(exchange, 200, "ok");
            return;
        }
        if (!path.startsWith(SESSION_PREFIX) || path.length() == SESSION_PREFIX.length()) {
            HttpResponses.text(exchange, 404, "Not found");
            return;
        }

        String origin = headers.getFirst("Origin");
        if (!originPolicy.allowsOrigin(origin)) {
            LOG.log(System.Logger.Level.WARNING, "Rejected request from origin {0}", origin);
            HttpResponses.text(exchange, 403, "Origin not allowed: " + origin + " (see --allow-origin)");
            return;
        }
        allowCors(exchange, origin);

        switch (method) {
            case "OPTIONS" -> {
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");
                HttpResponses.text(exchange, 204, null);
            }
            case "POST" -> handleSession(exchange, path.substring(SESSION_PREFIX.length()));
            default -> HttpResponses.text(exchange, 405, "Method not allowed");
        }
    }

    private void handleSession(HttpExchange exchange, String sessionId) throws IOException {
        long start = System.nanoTime();
        List<Object> commands;
        try {
            List<?> messages = Messages.list(Json.parse(readBody(exchange)));
            commands = sessions.handle(sessionId, messages);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Code server connection failed for session " + sessionId, e);
            commands = List.of(Messages.of("error", "Code server connection failed: " + e.getMessage()));
        } catch (BridgeProtocolException | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "Protocol error in session " + sessionId, e);
            commands = List.of(Messages.of("error", e.getMessage()));
        }
        HttpResponses.text(exchange, 200, Json.write(commands));
        sessions.recordHttp(sessionId, System.nanoTime() - start);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void allowCors(HttpExchange exchange, String origin) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", origin);
        headers.set("Vary", "Origin");
    }
}
