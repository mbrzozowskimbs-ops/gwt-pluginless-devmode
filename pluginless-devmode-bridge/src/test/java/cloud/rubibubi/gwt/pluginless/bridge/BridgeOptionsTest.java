package cloud.rubibubi.gwt.pluginless.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BridgeOptions")
class BridgeOptionsTest {

    @Test
    @DisplayName("Should use defaults without arguments")
    void shouldUseDefaults() {
        // when
        BridgeOptions options = BridgeOptions.parse();

        // then
        assertThat(options.port()).isEqualTo(9876);
        assertThat(options.codeServerHost()).isEqualTo("127.0.0.1");
        assertThat(options.codeServerPort()).isEqualTo(9997);
        assertThat(options.trace()).isFalse();
        assertThat(options.originPolicy().allowsOrigin("http://localhost:8080")).isTrue();
    }

    @Test
    @DisplayName("Should parse all options")
    void shouldParseOptions() {
        // when
        BridgeOptions options = BridgeOptions.parse("--port", "9000", "--code-server", "devbox:9998",
                "--allow-origin", "http://app.local:8080", "--trace");

        // then
        assertThat(options.port()).isEqualTo(9000);
        assertThat(options.codeServerHost()).isEqualTo("devbox");
        assertThat(options.codeServerPort()).isEqualTo(9998);
        assertThat(options.trace()).isTrue();
        assertThat(options.originPolicy().allowsOrigin("http://app.local:8080")).isTrue();
        assertThat(options.originPolicy().allowsOrigin("http://localhost:8080")).isFalse();
    }

    @Test
    @DisplayName("Should take code server port and -war from DevMode arguments and the rest from pluginless.* properties")
    void shouldDeriveOptionsFromDevModeArguments() {
        // given
        String[] devModeArgs = {"-nosuperDevMode", "-noserver", "-war", "target/war", "-codeServerPort", "9998",
                "-logLevel", "INFO", "com.example.app.App"};
        Properties properties = new Properties();
        properties.setProperty("pluginless.port", "9000");
        properties.setProperty("pluginless.proxy", "http://localhost:8080/");
        properties.setProperty("pluginless.warContext", "/myapp");
        properties.setProperty("pluginless.allowOrigin", "http://a.local:1, http://b.local:2");

        // when
        BridgeOptions options = BridgeOptions.forDevMode(devModeArgs, properties);

        // then
        assertThat(options.port()).isEqualTo(9000);
        assertThat(options.codeServerPort()).isEqualTo(9998);
        assertThat(options.war()).isEqualTo(Path.of("target/war"));
        assertThat(options.appServer()).isEqualTo(URI.create("http://localhost:8080"));
        assertThat(options.warContext()).isEqualTo("/myapp");
        assertThat(options.originPolicy().allowsOrigin("http://b.local:2")).isTrue();
        assertThat(options.trace()).isFalse();
    }

    @Test
    @DisplayName("Should use DevMode defaults without arguments or properties")
    void shouldUseDevModeDefaults() {
        // when
        BridgeOptions options = BridgeOptions.forDevMode(new String[] {"cloud.rubibubi.gwt.pluginless.sample.Sample"},
                new Properties());

        // then
        assertThat(options.port()).isEqualTo(9876);
        assertThat(options.codeServerPort()).isEqualTo(9997);
        assertThat(options.war()).isEqualTo(Path.of("war"));
        assertThat(options.appServer()).isNull();
    }

    @Test
    @DisplayName("Should leave the code server port to DevMode for -codeServerPort auto")
    void shouldAcceptAutoCodeServerPort() {
        // when
        BridgeOptions options = BridgeOptions.forDevMode(new String[] {"-codeServerPort", "auto",
                "cloud.rubibubi.gwt.pluginless.sample.Sample"}, new Properties());

        // then: 0 like in DevMode, set once DevMode has bound its code server
        assertThat(options.codeServerPort()).isZero();
    }

    @Test
    @DisplayName("Should reject unknown or incomplete options")
    void shouldRejectInvalidOptions() {
        assertThatThrownBy(() -> BridgeOptions.parse("--verbose")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BridgeOptions.parse("--port")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BridgeOptions.parse("--port", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BridgeOptions.parse("--port", "70000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BridgeOptions.parse("--code-server", "9997"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject an application server URL with a query or fragment")
    void shouldRejectProxyWithQueryOrFragment() {
        assertThatThrownBy(() -> BridgeOptions.parse("--proxy", "http://localhost:8080/app?tenant=a"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("query or fragment");
        assertThatThrownBy(() -> BridgeOptions.parse("--proxy", "http://localhost:8080/app#top"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("query or fragment");
    }
}
