package cloud.rubibubi.gwt.pluginless.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PlatformJavaClasses")
class PlatformJavaClassesTest {

    @Test
    @DisplayName("Should expose java.* classes of platform modules as JDK classes")
    void shouldExposePlatformJavaClasses() {
        // when
        PlatformJavaClasses.install();

        // then
        Map<String, Class<?>> bridgeClasses = PlatformJavaClasses.bridgeClassNames();
        assertThat(bridgeClasses.get("java.sql.Timestamp")).isSameAs(java.sql.Timestamp.class);
        assertThat(bridgeClasses.get("java.sql.Date")).isSameAs(java.sql.Date.class);
        assertThat(bridgeClasses).doesNotContainKey("java.lang.String");
    }
}
