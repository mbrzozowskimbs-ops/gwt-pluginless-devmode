package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * Serves the application through the bridge, so that the page, the DevMode output and the bridge share one origin.
 * <ul>
 *     <li>{@code GET /policies/<strongName>.gwt.rpc} — serialization policies written by DevMode; the application
 *     server downloads them when started with {@code -Dgwt.codeserver.port=<bridge port>}, the same mechanism
 *     {@code RemoteServiceServlet} offers for Super Dev Mode,</li>
 *     <li>files present in the DevMode {@code -war} directory (above all {@code <module>.devmode.js}),</li>
 *     <li>everything else is forwarded to the application server unchanged.</li>
 * </ul>
 * Forwarded requests keep the browser's {@code Host} header, so the application server builds redirects and
 * absolute URLs that point back at the bridge.
 */
final class AppServerHandler implements HttpHandler {

    static final String POLICIES_PREFIX = "/policies/";

    private static final System.Logger LOG = System.getLogger(AppServerHandler.class.getName());

    static {
        // Lets forward() keep the browser's Host header; read when java.net.http initializes, i.e. by the first
        // HttpClient, which this class creates
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }

    // Hop-by-hop headers (RFC 9110 7.6.1) and headers the HTTP client sets itself
    private static final List<String> HOP_BY_HOP = List.of("connection", "keep-alive", "proxy-connection", "te",
            "trailer", "transfer-encoding", "upgrade", "content-length");
    private static final Set<String> NOT_FORWARDED_REQUEST = with(HOP_BY_HOP, "expect");
    private static final Set<String> NOT_FORWARDED_RESPONSE = with(HOP_BY_HOP, ":status");

    // Text types with their charset; anything else as guessed by the JDK
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "js", "text/javascript; charset=utf-8",
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "rpc", "text/plain; charset=utf-8");

    private final DevModeWar war;
    private final URI appServer;
    private final HttpClient client;

    /**
     * @param war       DevMode output directory, may be {@code null}
     * @param appServer application server base URL, may be {@code null}
     */
    AppServerHandler(DevModeWar war, URI appServer) {
        this.war = war;
        this.appServer = appServer;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            HttpResponses.text(exchange, 503, "Interrupted");
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Request failed: " + exchange.getRequestURI(), e);
            HttpResponses.text(exchange, 502, "Application server unreachable at " + appServer + ": " + e);
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException, InterruptedException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        boolean read = "GET".equals(method) || "HEAD".equals(method);

        if (path.startsWith(POLICIES_PREFIX)) {
            Optional<Path> policy = war == null ? Optional.empty()
                    : war.findPolicy(path.substring(POLICIES_PREFIX.length()));
            if (policy.isPresent() && read) {
                sendFile(exchange, policy.get());
            } else {
                HttpResponses.text(exchange, 404, "No such policy in the DevMode -war directory");
            }
            return;
        }
        if (war != null && read) {
            Optional<Path> file = war.resolve(path);
            if (file.isPresent()) {
                sendFile(exchange, file.get());
                return;
            }
        }
        if (appServer == null) {
            HttpResponses.text(exchange, 404, "Not found (start the bridge with --proxy to forward to the application server)");
            return;
        }
        forward(exchange);
    }

    private void forward(HttpExchange exchange) throws IOException, InterruptedException {
        // Appended, not resolved: resolve() would drop the base path and read a path starting with // as a host
        URI requestUri = exchange.getRequestURI();
        URI target = URI.create(appServer + requestUri.getRawPath()
                + (requestUri.getRawQuery() == null ? "" : "?" + requestUri.getRawQuery()));
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .method(exchange.getRequestMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            if (NOT_FORWARDED_REQUEST.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String value : header.getValue()) {
                try {
                    request.header(header.getKey(), value);
                } catch (IllegalArgumentException e) {
                    // Host stays restricted if java.net.http initialized before this class set
                    // jdk.httpclient.allowRestrictedHeaders; then the target's own Host is used
                    LOG.log(System.Logger.Level.DEBUG, "Header not forwarded: {0}", header.getKey());
                }
            }
        }

        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        Headers headers = exchange.getResponseHeaders();
        response.headers().map().forEach((name, values) -> {
            if (!NOT_FORWARDED_RESPONSE.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, values);
            }
        });
        boolean noBody = "HEAD".equals(exchange.getRequestMethod())
                || response.statusCode() == 204 || response.statusCode() == 304;
        try (InputStream in = response.body()) {
            if (noBody) {
                exchange.sendResponseHeaders(response.statusCode(), -1);
                return;
            }
            long length = response.headers().firstValueAsLong("Content-Length").orElse(0);
            exchange.sendResponseHeaders(response.statusCode(), length);
            try (OutputStream out = exchange.getResponseBody()) {
                in.transferTo(out);
            }
        }
    }

    private static void sendFile(HttpExchange exchange, Path file) throws IOException {
        String name = file.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        Headers headers = exchange.getResponseHeaders();
        String contentType = CONTENT_TYPES.get(extension);
        if (contentType == null) {
            contentType = Objects.requireNonNullElse(URLConnection.guessContentTypeFromName(name),
                    "application/octet-stream");
        }
        headers.set("Content-Type", contentType);
        // DevMode rewrites these files on every module reload
        headers.set("Cache-Control", "no-store");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        long size = Files.size(file);
        exchange.sendResponseHeaders(200, size == 0 ? -1 : size);
        try (OutputStream out = exchange.getResponseBody()) {
            Files.copy(file, out);
        }
    }

    private static Set<String> with(List<String> names, String name) {
        Set<String> set = new HashSet<>(names);
        set.add(name);
        return Set.copyOf(set);
    }
}
