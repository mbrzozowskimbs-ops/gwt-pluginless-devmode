package cloud.rubibubi.gwt.pluginless.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.sun.net.httpserver.HttpServer;

@DisplayName("Bridge in front of an application server")
@Timeout(10)
class AppServerHandlerTest {

    private static final String POLICY = "0123456789ABCDEF0123456789ABCDEF.gwt.rpc";

    @TempDir
    Path war;

    private final HttpClient http = HttpClient.newHttpClient();
    private HttpServer appServer;
    private BridgeServer bridge;

    @BeforeEach
    void start() throws IOException {
        Files.createDirectories(war.resolve("frontend"));
        Files.writeString(war.resolve("frontend/frontend.devmode.js"), "// pluginless devmode");
        Files.writeString(war.resolve("frontend").resolve(POLICY), "policy");
        Files.writeString(war.resolve("secret.txt"), "outside the context");
        Files.createDirectories(war.resolve("WEB-INF"));
        Files.writeString(war.resolve("WEB-INF/web.xml"), "<web-app/>");

        appServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        appServer.createContext("/", exchange -> {
            byte[] requestBody;
            try (InputStream in = exchange.getRequestBody()) {
                requestBody = in.readAllBytes();
            }
            if (exchange.getRequestURI().getPath().equals("/myapp/login")) {
                exchange.getResponseHeaders().set("Location", "/myapp/frontend/Application.html");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            String text = "app:" + exchange.getRequestMethod() + " " + exchange.getRequestURI()
                    + " body=" + new String(requestBody, StandardCharsets.UTF_8)
                    + " cookie=" + exchange.getRequestHeaders().getFirst("Cookie");
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Set-Cookie", "JSESSIONID=abc; Path=/myapp");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        appServer.start();

        URI appServerUri = URI.create("http://127.0.0.1:" + appServer.getAddress().getPort());
        bridge = BridgeServer.start(new BridgeOptions(0, "127.0.0.1", 9997, OriginPolicy.loopback(), false,
                appServerUri, war, "/myapp"));
    }

    @AfterEach
    void stop() {
        bridge.close();
        appServer.stop(0);
    }

    @Test
    @DisplayName("Should serve the DevMode bootstrap script from the -war directory")
    void shouldServeDevModeFile() throws Exception {
        // when
        HttpResponse<String> response = get("/myapp/frontend/frontend.devmode.js");

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("// pluginless devmode");
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/javascript"));
    }

    @Test
    @DisplayName("Should serve DevMode serialization policies for the application server")
    void shouldServePolicy() throws Exception {
        // when
        HttpResponse<String> response = get(AppServerHandler.POLICIES_PREFIX + POLICY);

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("policy");
    }

    @Test
    @DisplayName("Should refuse policy names that are not strong names")
    void shouldRefuseInvalidPolicyName() throws Exception {
        assertThat(get("/policies/..%2Fsecret.txt").statusCode()).isEqualTo(404);
        assertThat(get("/policies/FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF.gwt.rpc").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("Should forward other requests with method, query, body and cookies")
    void shouldForwardToApplicationServer() throws Exception {
        // given
        HttpRequest request = HttpRequest.newBuilder(uri("/myapp/frontend/dispatch?x=1"))
                .header("Cookie", "JSESSIONID=abc")
                .POST(HttpRequest.BodyPublishers.ofString("rpc-payload"))
                .build();

        // when
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .isEqualTo("app:POST /myapp/frontend/dispatch?x=1 body=rpc-payload cookie=JSESSIONID=abc");
        assertThat(response.headers().firstValue("Set-Cookie")).contains("JSESSIONID=abc; Path=/myapp");
    }

    @Test
    @DisplayName("Should not serve -war files outside the context prefix")
    void shouldNotServeFilesOutsideContext() throws Exception {
        // when
        HttpResponse<String> response = get("/secret.txt");

        // then
        assertThat(response.body()).startsWith("app:GET /secret.txt");
    }

    @Test
    @DisplayName("Should not serve WEB-INF and META-INF from the -war directory")
    void shouldNotServeWebInf() throws Exception {
        for (String path : new String[]{"/myapp/WEB-INF/web.xml", "/myapp/web-inf/web.xml",
                "/myapp/frontend/../WEB-INF/web.xml"}) {
            // when
            HttpResponse<String> response = get(path);

            // then: forwarded to the application server, which decides itself
            assertThat(response.body()).as(path).startsWith("app:GET ").doesNotContain("<web-app/>");
        }
    }

    @Test
    @DisplayName("Should forward a path starting with // to the application server, not to the host it names")
    void shouldNotForwardToHostInPath() throws Exception {
        // when
        HttpResponse<String> response = get("////attacker.invalid/x");

        // then: forwarded to the application server or, on JDK updates whose HTTP server refuses a request target
        // starting with // (e.g. 17.0.20), rejected before it reaches the handler; never sent to the host it names
        if (response.statusCode() == 400) {
            assertThat(response.body()).contains("Bad request URI");
        } else {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).startsWith("app:GET ").contains("attacker.invalid/x");
        }
    }

    // A request URI ////host/x has an empty authority and the path //host/x, whatever the JDK HTTP server accepts
    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @DisplayName("Should append the request path and query to the application server URL")
    @CsvSource({
            "http://127.0.0.1:8080, ////attacker.invalid/x?a=%2F, http://127.0.0.1:8080//attacker.invalid/x?a=%2F",
            "http://127.0.0.1:8080/base, ////attacker.invalid/x?a=%2F, http://127.0.0.1:8080/base//attacker.invalid/x?a=%2F",
            "http://127.0.0.1:8080/base, /myapp/x, http://127.0.0.1:8080/base/myapp/x",
            "http://127.0.0.1:8080/base/?q=1#f, /myapp/x?y=2, http://127.0.0.1:8080/base/myapp/x?y=2"})
    void shouldAppendRequestToAppServerUrl(String appServerUrl, String requestUri, String expected) {
        // when
        URI target = AppServerHandler.forwardTarget(URI.create(appServerUrl), URI.create(requestUri));

        // then
        assertThat(target.getHost()).isEqualTo("127.0.0.1");
        assertThat(target).hasToString(expected);
    }

    @Test
    @DisplayName("Should answer 400 for a request java.net.http cannot forward")
    void shouldRejectUnforwardableRequest() throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), bridge.port())) {
            // when: CONNECT, which HttpRequest.Builder refuses
            socket.getOutputStream().write("CONNECT /myapp/x HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();

            // then
            assertThat(statusLine).startsWith("HTTP/1.1 400");
        }
    }

    @Test
    @DisplayName("Should keep the base path of the application server URL")
    void shouldKeepAppServerBasePath() throws Exception {
        // given
        URI appServerUri = URI.create("http://127.0.0.1:" + appServer.getAddress().getPort() + "/base");
        try (BridgeServer based = BridgeServer.start(new BridgeOptions(0, "127.0.0.1", 9997,
                OriginPolicy.loopback(), false, appServerUri, null, "/"))) {
            // when
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + based.port() + "/foo?x=1")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            // then
            assertThat(response.body()).startsWith("app:GET /base/foo?x=1");
        }
    }

    @Test
    @DisplayName("Should pass redirects to the browser instead of following them")
    void shouldPassRedirects() throws Exception {
        // when
        HttpResponse<String> response = get("/myapp/login");

        // then
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains("/myapp/frontend/Application.html");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + bridge.port() + path);
    }
}
