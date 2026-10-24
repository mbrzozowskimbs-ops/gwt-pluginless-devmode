package cloud.rubibubi.gwt.pluginless.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.gwt.dev.About;

@DisplayName("GwtVersionCheck")
class GwtVersionCheckTest {

    @Test
    @DisplayName("Should know the gwt-dev version it is built with, the one on the test classpath")
    void shouldReadBuildVersion() {
        // when / then
        assertThat(GwtVersionCheck.builtWith())
                .isNotBlank()
                .isEqualTo(About.getGwtVersionNum());
    }

    @Test
    @DisplayName("Should name both versions when they differ")
    void shouldWarnOnMismatch() {
        // when / then
        assertThat(GwtVersionCheck.mismatch("2.12.2", "2.11.0"))
                .hasValueSatisfying(warning -> assertThat(warning)
                        .contains("built with GWT 2.12.2")
                        .contains("gwt-dev 2.11.0 is on the classpath"));
    }

    @ParameterizedTest(name = "built with {0}, running {1}")
    @CsvSource(value = {"2.12.2, 2.12.2", "2.12.2, 0.0.0", "NULL, 2.12.2", "2.12.2, NULL"}, nullValues = "NULL")
    @DisplayName("Should not warn when the versions match or one of them is unknown")
    void shouldNotWarn(String builtWith, String running) {
        // when / then
        assertThat(GwtVersionCheck.mismatch(builtWith, running)).isEmpty();
    }
}
