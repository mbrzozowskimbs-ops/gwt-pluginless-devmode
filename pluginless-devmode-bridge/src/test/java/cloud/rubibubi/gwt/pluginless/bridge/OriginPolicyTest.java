package cloud.rubibubi.gwt.pluginless.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("OriginPolicy")
class OriginPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "https://localhost", "http://127.0.0.1:8888", "http://[::1]:80",
            "http://LOCALHOST:1"})
    @DisplayName("Should allow loopback origins by default")
    void shouldAllowLoopbackOrigins(String origin) {
        assertThat(OriginPolicy.loopback().allowsOrigin(origin)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"null", "http://evil.example", "http://localhost.evil.example", "http://127.0.0.2",
            "not a uri"})
    @DisplayName("Should reject other origins by default")
    void shouldRejectOtherOrigins(String origin) {
        assertThat(OriginPolicy.loopback().allowsOrigin(origin)).isFalse();
    }

    @Test
    @DisplayName("Should allow exactly the configured origins")
    void shouldAllowConfiguredOrigins() {
        // given
        OriginPolicy policy = OriginPolicy.of(List.of("http://app.local:8080/"));

        // when / then
        assertThat(policy.allowsOrigin("http://app.local:8080")).isTrue();
        assertThat(policy.allowsOrigin("http://localhost:8080")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1:9876", "localhost", "localhost:9876", "[::1]:9876"})
    @DisplayName("Should accept loopback Host headers")
    void shouldAcceptLoopbackHosts(String host) {
        assertThat(OriginPolicy.isLoopbackHost(host)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"evil.example:9876", "rebind.example", "[::2]:1", "127.0.0.1.evil.example"})
    @DisplayName("Should reject other Host headers (DNS rebinding)")
    void shouldRejectOtherHosts(String host) {
        assertThat(OriginPolicy.isLoopbackHost(host)).isFalse();
    }
}
