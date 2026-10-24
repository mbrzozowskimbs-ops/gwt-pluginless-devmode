package cloud.rubibubi.gwt.pluginless.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("ElectronDistribution")
class ElectronDistributionTest {

    @TempDir
    Path temp;

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "Mac OS X, aarch64, darwin-arm64",
            "Mac OS X, x86_64, darwin-x64",
            "Windows 11, amd64, win32-x64",
            "Linux, amd64, linux-x64",
            "Linux, aarch64, linux-arm64"})
    @DisplayName("Should map the JVM operating system and architecture to the Electron platform")
    void shouldMapPlatform(String osName, String osArch, String platform) {
        // when / then
        assertThat(ElectronDistribution.platform(osName, osArch)).isEqualTo(platform);
    }

    @Test
    @DisplayName("Should reject a platform Electron is not built for")
    void shouldRejectUnknownPlatform() {
        // when / then
        assertThatThrownBy(() -> ElectronDistribution.platform("SunOS", "sparcv9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SunOS sparcv9");
    }

    @Test
    @DisplayName("Should unpack Electron and the app files into the cache directory")
    void shouldInstall() throws Exception {
        // given
        Path cache = temp.resolve("cache");

        // when
        ElectronDistribution.Installation installation;
        try (URLClassLoader loader = distribution("linux-x64", "app/bin/run")) {
            installation = ElectronDistribution.install(loader, cache, "linux-x64");
        }

        // then
        assertThat(installation.executable()).isEqualTo(cache.resolve("electron/45.0.0-linux-x64/app/bin/run"));
        assertThat(installation.executable()).exists();
        assertThat(installation.app().getParent()).isEqualTo(cache.resolve("app"));
        for (String file : ElectronDistribution.APP_FILES) {
            assertThat(installation.app().resolve(file)).exists();
        }
    }

    @Test
    @DisplayName("Should not unpack Electron again once the unpack is complete")
    void shouldKeepCompleteInstallation() throws Exception {
        // given
        Path cache = temp.resolve("cache");
        try (URLClassLoader loader = distribution("linux-x64", "app/bin/run")) {
            ElectronDistribution.install(loader, cache, "linux-x64");
            Path readme = cache.resolve("electron/45.0.0-linux-x64/app/readme.txt");
            Files.writeString(readme, "changed");

            // when
            ElectronDistribution.install(loader, cache, "linux-x64");

            // then
            assertThat(readme).hasContent("changed");
        }
    }

    @Test
    @DisplayName("Should unpack Electron again after an interrupted unpack")
    void shouldRedoInterruptedUnpack() throws Exception {
        // given
        Path cache = temp.resolve("cache");
        Path leftover = Files.createDirectories(cache.resolve("electron/45.0.0-linux-x64"))
                .resolve("partial.tmp");
        Files.writeString(leftover, "interrupted");

        // when
        try (URLClassLoader loader = distribution("linux-x64", "app/bin/run")) {
            ElectronDistribution.install(loader, cache, "linux-x64");
        }

        // then
        assertThat(leftover).doesNotExist();
        assertThat(cache.resolve("electron/45.0.0-linux-x64/app/readme.txt")).hasContent("hello");
    }

    @Test
    @DisplayName("Should name the platforms found when there is no Electron build for the current one")
    void shouldFailWithoutDistributionForPlatform() throws Exception {
        // when / then
        try (URLClassLoader loader = distribution("win32-x64", "electron.exe")) {
            assertThatThrownBy(() -> ElectronDistribution.install(loader, temp.resolve("cache"), "darwin-arm64"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("No pluginless-devmode-electron-dist for darwin-arm64")
                    .hasMessageContaining("found: win32-x64");
        }
    }

    @Test
    @DisplayName("Should bundle every file of the pluginless-devmode-electron app")
    void shouldListAllAppFiles() throws IOException {
        // given: Surefire runs in the module directory
        try (Stream<Path> files = Files.list(Path.of("../pluginless-devmode-electron"))) {
            // when / then
            assertThat(files.map(path -> path.getFileName().toString())
                    .filter(name -> name.equals("package.json") || name.matches(".*\\.(js|html|css)")))
                    .containsExactlyInAnyOrderElementsOf(ElectronDistribution.APP_FILES);
        }
    }

    /**
     * Class loader with a pluginless-devmode-electron-dist layout made of the plain.zip test archive (no symbolic links, so it
     * also works on Windows); the app files come from the parent, i.e. the bridge's own resources.
     */
    private URLClassLoader distribution(String platform, String executable) throws Exception {
        Path root = Files.createDirectories(temp.resolve("dist-" + platform).resolve("META-INF/pluginless-devmode"));
        Files.copy(UnixZipTest.resource("plain.zip"), root.resolve("electron.zip"));
        Files.writeString(root.resolve("electron.properties"),
                "version=45.0.0\nplatform=" + platform + "\nexecutable=" + executable + "\n");
        return new URLClassLoader(new URL[]{temp.resolve("dist-" + platform).toUri().toURL()},
                getClass().getClassLoader());
    }
}
