package cloud.rubibubi.gwt.pluginless.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gwt.dev.shell.BrowserChannel.SessionHandler.SpecialDispatchId;

@DisplayName("BridgeSession against a BrowserChannel code server")
@Timeout(10)
class BridgeSessionTest {

    static final List<Object> CONNECT = Messages.of("connect", "2.1",
            "http://localhost:8888/sample/Sample.html", "", "session-key", "sample", "test-agent");

    private ServerSocket serverSocket;
    private BridgeSession session;

    @BeforeEach
    void startCodeServerSocket() throws IOException {
        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    @AfterEach
    void cleanUp() throws IOException {
        if (session != null) {
            session.close();
        }
        serverSocket.close();
    }

    @Test
    @DisplayName("Should relay module load, calls in both directions, field access, frees and quit")
    void shouldRelayFullSession() throws Exception {
        // given
        CompletableFuture<Void> codeServer = runCodeServer(server -> {
            FakeCodeServer.LoadModule load = server.handshake();
            assertThat(load.moduleName()).isEqualTo("sample");
            assertThat(load.sessionKey()).isEqualTo("session-key");
            assertThat(load.userAgent()).isEqualTo("test-agent");
            server.requestIconAndExpectNone();
            server.sendJsni("function __defineStatic(a) { window.__static = a; }");
            server.sendInvoke("alert", FakeCodeServer.nullValue(), ValueCodec.stringValue("hi"));
            assertThat(server.expectReturn()).isEqualTo(new FakeCodeServer.Returned(false, 42));
            server.sendReturn(false, FakeCodeServer.undefined());

            assertThat(server.expectInvoke()).isEqualTo(new FakeCodeServer.Invoked(5, List.of("j", 1), List.of(3)));
            server.sendReturn(false, ValueCodec.intValue(6));

            assertThat(server.expectInvokeSpecial())
                    .isEqualTo(new FakeCodeServer.Special(SpecialDispatchId.GetProperty, List.of(1, 7)));
            server.sendReturn(false, ValueCodec.stringValue("field"));

            assertThat(server.expectFree()).containsExactly(4, 5);
            assertThat(server.expectInvokeSpecial())
                    .isEqualTo(new FakeCodeServer.Special(SpecialDispatchId.SetProperty, List.of(1, 8, true)));
            server.sendReturn(false, FakeCodeServer.undefined());

            server.expectQuit();
        });
        session = openSession("s1");

        // when / then
        assertThat(session.handle(List.of(CONNECT))).isEqualTo(List.of(
                List.of("jsni", "function __defineStatic(a) { window.__static = a; }"),
                Arrays.asList("invoke", "alert", null, List.of("hi"))));
        assertThat(session.handle(List.of(List.of("return", false, 42L))))
                .isEqualTo(List.of(List.of("return", false, List.of("u"))));
        assertThat(session.handle(List.of(List.of("invoke", 5L, List.of("j", 1L), List.of(3L)))))
                .isEqualTo(List.of(List.of("return", false, 6)));
        assertThat(session.handle(List.of(List.of("getJava", 1L, 7L))))
                .isEqualTo(List.of(List.of("return", false, "field")));
        assertThat(session.handle(List.of(List.of("free", List.of(4L, 5L)), List.of("setJava", 1L, 8L, true))))
                .isEqualTo(List.of(List.of("return", false, List.of("u"))));
        assertThat(session.handle(List.of(List.of("quit")))).isEmpty();
        assertThat(session.isClosed()).isTrue();
        codeServer.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should turn a code server property request on a JS object into a get command")
    void shouldForwardJsPropertyRequest() throws Exception {
        // given
        CompletableFuture<Void> codeServer = runCodeServer(server -> {
            server.handshake();
            server.sendGetProperty(3, "x");
            assertThat(server.expectReturn()).isEqualTo(new FakeCodeServer.Returned(false, 1));
            server.sendReturn(false, FakeCodeServer.undefined());
        });
        session = openSession("s2");

        // when
        List<Object> commands = session.handle(List.of(CONNECT));

        // then
        assertThat(commands).isEqualTo(List.of(List.of("get", 3, "x")));
        assertThat(session.handle(List.of(List.of("return", false, 1L))))
                .isEqualTo(List.of(List.of("return", false, List.of("u"))));
        codeServer.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should fail and close the session when the code server refuses the protocol version")
    void shouldFailOnRefusedVersion() throws Exception {
        // given
        CompletableFuture<Void> codeServer = runCodeServer(server -> server.refuseVersion("hosted.html too old"));
        session = openSession("s3");

        // when / then
        assertThatThrownBy(() -> session.handle(List.of(CONNECT)))
                .isInstanceOf(BridgeProtocolException.class)
                .hasMessageContaining("hosted.html too old");
        assertThat(session.isClosed()).isTrue();
        codeServer.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should fail and close the session when the code server disconnects")
    void shouldFailWhenCodeServerDisconnects() throws Exception {
        // given
        CompletableFuture<Void> codeServer = runCodeServer(server -> {
            server.handshake();
            server.endSession();
        });
        session = openSession("s4");

        // when / then
        assertThatThrownBy(() -> session.handle(List.of(CONNECT))).isInstanceOf(IOException.class);
        assertThat(session.isClosed()).isTrue();
        codeServer.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should reject an unknown browser message and close the session")
    void shouldRejectUnknownMessage() throws Exception {
        // given
        CompletableFuture<Void> codeServer = runCodeServer(server -> {
        });
        session = openSession("s5");

        // when / then
        assertThatThrownBy(() -> session.handle(List.of(List.of("teleport"))))
                .isInstanceOf(BridgeProtocolException.class)
                .hasMessageContaining("teleport");
        assertThat(session.isClosed()).isTrue();
        codeServer.get(5, TimeUnit.SECONDS);
    }

    private BridgeSession openSession(String id) throws IOException {
        return BridgeSession.open(id, "127.0.0.1", serverSocket.getLocalPort(), BridgeTrace.disabled());
    }

    private CompletableFuture<Void> runCodeServer(CodeServerScript script) {
        return CompletableFuture.runAsync(() -> {
            try {
                script.run(FakeCodeServer.accept(serverSocket));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @FunctionalInterface
    interface CodeServerScript {
        void run(FakeCodeServer server) throws Exception;
    }
}
