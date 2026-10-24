package cloud.rubibubi.gwt.pluginless.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ElectronBrowser")
class ElectronBrowserTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("Should point gwt.codesvr of an absolute startup URL to the bridge")
    void shouldAddBridgeToAbsoluteUrl() {
        // when
        String url = ElectronBrowser.pageUrl("http://localhost:9876/myapp/frontend/Application.html", 8888, 9876);

        // then
        assertThat(url)
                .isEqualTo("http://localhost:9876/myapp/frontend/Application.html?gwt.codesvr=127.0.0.1:9876");
    }

    @Test
    @DisplayName("Should resolve a relative startup URL against the DevMode web server")
    void shouldResolveRelativeUrl() {
        // when
        String url = ElectronBrowser.pageUrl("/sample/Sample.html?locale=pl", 8888, 9000);

        // then
        assertThat(url).isEqualTo("http://localhost:8888/sample/Sample.html?locale=pl&gwt.codesvr=127.0.0.1:9000");
    }

    @Test
    @DisplayName("Should put gwt.codesvr into the query string, before the fragment")
    void shouldAddBridgeBeforeFragment() {
        assertThat(ElectronBrowser.pageUrl("App.html#login", 8888, 9876))
                .isEqualTo("http://localhost:8888/App.html?gwt.codesvr=127.0.0.1:9876#login");
        assertThat(ElectronBrowser.pageUrl("App.html#x?y=1", 8888, 9876))
                .isEqualTo("http://localhost:8888/App.html?gwt.codesvr=127.0.0.1:9876#x?y=1");
        assertThat(ElectronBrowser.pageUrl("App.html?a=1#login", 8888, 9876))
                .isEqualTo("http://localhost:8888/App.html?a=1&gwt.codesvr=127.0.0.1:9876#login");
    }

    @Test
    @DisplayName("Should take the platform executable from path.txt of the electron npm package")
    void shouldResolveExecutable() throws IOException {
        // given
        Path electronPackage = Files.createDirectories(directory.resolve("node_modules/electron"));
        Files.writeString(electronPackage.resolve("path.txt"), "electron.exe");

        // when
        Path executable = ElectronBrowser.executable(directory);

        // then
        assertThat(executable).isEqualTo(electronPackage.resolve("dist/electron.exe"));
    }

    @Test
    @DisplayName("Should fail when the electron npm package is not installed")
    void shouldFailWithoutElectron() {
        // when / then
        assertThatThrownBy(() -> ElectronBrowser.executable(directory))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("mvn install");
    }

    @Test
    @DisplayName("Should point to an undefined IntelliJ path variable left in the directory")
    void shouldHintUndefinedPathVariable() {
        // when / then
        assertThatThrownBy(() -> ElectronBrowser.executable(directory.resolve("$PLUGINLESS_HOME$/pluginless-devmode-electron")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Path Variables");
    }

    @Test
    @DisplayName("Should disable the Chromium sandbox only on Linux")
    void shouldDisableSandboxOnLinux() {
        // given
        Path executable = Path.of("electron");
        Path app = Path.of("pluginless-devmode-electron");

        // when / then
        assertThat(ElectronBrowser.command(executable, app, "http://page", "Linux"))
                .containsExactly("electron", "pluginless-devmode-electron", "--no-sandbox", "http://page");
        assertThat(ElectronBrowser.command(executable, app, "http://page", "Windows 11"))
                .containsExactly("electron", "pluginless-devmode-electron", "http://page");
        assertThat(ElectronBrowser.command(executable, app, "http://page", "Mac OS X"))
                .containsExactly("electron", "pluginless-devmode-electron", "http://page");
    }
}
