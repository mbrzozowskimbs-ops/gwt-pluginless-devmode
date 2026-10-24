package cloud.rubibubi.gwt.pluginless.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@DisplayName("BridgeServer over HTTP")
@Timeout(10)
class BridgeServerTest {

    private static final String PAGE_ORIGIN = "http://localhost:8888";

    private final HttpClient http = HttpClient.newHttpClient();
    private ServerSocket codeServerSocket;
    private BridgeServer bridge;

    @BeforeEach
    void start() throws IOException {
        codeServerSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        bridge = BridgeServer.start(new BridgeOptions(0, "127.0.0.1", codeServerSocket.getLocalPort(),
                OriginPolicy.loopback(), false));
    }

    @AfterEach
    void stop() throws IOException {
        bridge.close();
        codeServerSocket.close();
    }

    @Test
    @DisplayName("Should relay a session request and allow the page origin via CORS")
    void shouldRelaySessionRequest() throws Exception {
        // given
        CompletableFuture<Void> codeServer = CompletableFuture.runAsync(() -> {
            try {
                FakeCodeServer server = FakeCodeServer.accept(codeServerSocket);
                server.handshake();
                server.sendReturn(false, FakeCodeServer.undefined());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        // when
        HttpResponse<String> response = post("/pluginless-devmode/session/abc", PAGE_ORIGIN,
                Json.write(List.of(BridgeSessionTest.CONNECT)));

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(PAGE_ORIGIN);
        assertThat(response.body()).isEqualTo("[[\"return\",false,[\"u\"]]]");
        codeServer.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should connect to a code server port set after the start (-codeServerPort auto)")
    void shouldUseCodeServerPortSetLater() throws Exception {
        // given
        bridge.close();
        bridge = BridgeServer.start(new BridgeOptions(0, "127.0.0.1", 0, OriginPolicy.loopback(), false));
        HttpResponse<String> early = post("/pluginless-devmode/session/early", PAGE_ORIGIN,
                Json.write(List.of(BridgeSessionTest.CONNECT)));
        bridge.codeServerPort(codeServerSocket.getLocalPort());
        CompletableFuture<Void> codeServer = CompletableFuture.runAsync(() -> {
            try {
                FakeCodeServer server = FakeCodeServer.accept(codeServerSocket);
                server.handshake();
                server.sendReturn(false, FakeCodeServer.undefined());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        // when
        HttpResponse<String> response = post("/pluginless-devmode/session/abc", PAGE_ORIGIN,
                Json.write(List.of(BridgeSessionTest.CONNECT)));

        // then
        assertThat(early.body()).startsWith("[[\"error\"").contains("not started");
        assertThat(response.body()).isEqualTo("[[\"return\",false,[\"u\"]]]");
        codeServer.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should reject pages from a foreign origin")
    void shouldRejectForeignOrigin() throws Exception {
        // when
        HttpResponse<String> response = post("/pluginless-devmode/session/abc", "http://evil.example",
                Json.write(List.of(BridgeSessionTest.CONNECT)));

        // then
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    @DisplayName("Should report an unknown session as an error command")
    void shouldReportUnknownSession() throws Exception {
        // when
        HttpResponse<String> response = post("/pluginless-devmode/session/missing", PAGE_ORIGIN,
                "[[\"invoke\",1,null,[]]]");

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).startsWith("[[\"error\",\"Unknown session missing");
    }

    @Test
    @DisplayName("Should report malformed JSON as an error command")
    void shouldReportMalformedJson() throws Exception {
        // when
        HttpResponse<String> response = post("/pluginless-devmode/session/abc", PAGE_ORIGIN, "[[\"connect\"");

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).startsWith("[[\"error\",\"Invalid JSON");
    }

    @Test
    @DisplayName("Should answer the health check")
    void shouldAnswerHealthCheck() throws Exception {
        // when
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/pluginless-devmode/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ok");
    }

    private HttpResponse<String> post(String path, String origin, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Origin", origin)
                .header("Content-Type", "text/plain;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + bridge.port() + path);
    }
}
