package cloud.rubibubi.gwt.pluginless.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The zip files in the test resources are made with Python's zipfile, which stores Unix modes like Unix zip tools.
 */
@DisplayName("UnixZip")
class UnixZipTest {

    @TempDir
    Path target;

    @Test
    @DisplayName("Should read the Unix modes from the central directory")
    void shouldReadUnixModes() throws Exception {
        // when / then
        assertThat(UnixZip.unixModes(resource("unix.zip")))
                .containsEntry("app/", 040755)
                .containsEntry("app/readme.txt", 0100644)
                .containsEntry("app/bin/run", 0100755)
                .containsEntry("app/current", 0120755)
                .containsEntry("app/link.txt", 0120755);
    }

    @Test
    @DisplayName("Should unpack files with their permissions and symbolic links to files and directories")
    void shouldUnpackFilesAndSymbolicLinks() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX file system");

        // when
        UnixZip.unpack(resource("unix.zip"), target);

        // then
        assertThat(target.resolve("app/readme.txt")).hasContent("hello");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target.resolve("app/bin/run"))))
                .isEqualTo("rwxr-xr-x");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target.resolve("app/readme.txt"))))
                .isEqualTo("rw-r--r--");
        assertThat(Files.readSymbolicLink(target.resolve("app/current"))).isEqualTo(Path.of("bin"));
        assertThat(target.resolve("app/current/run")).exists();
        assertThat(Files.readSymbolicLink(target.resolve("app/link.txt"))).isEqualTo(Path.of("readme.txt"));
        assertThat(target.resolve("app/link.txt")).hasContent("hello");
    }

    @Test
    @DisplayName("Should reject an entry outside the target directory")
    void shouldRejectEntryOutsideTarget() {
        // when / then
        assertThatThrownBy(() -> UnixZip.unpack(resource("outside-entry.zip"), target.resolve("unpacked")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("../evil.txt");
        assertThat(target.resolve("evil.txt")).doesNotExist();
    }

    @Test
    @DisplayName("Should reject a symbolic link pointing outside the target directory")
    void shouldRejectSymbolicLinkOutsideTarget() {
        // when / then
        assertThatThrownBy(() -> UnixZip.unpack(resource("outside-link.zip"), target.resolve("unpacked")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("../../outside");
    }

    @Test
    @DisplayName("Should reject an entry written through a symbolic link")
    void shouldRejectEntryThroughSymbolicLink() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX file system");

        // when / then: x -> d/b/.. stays inside lexically, but d/b -> .. makes it the parent of the target
        assertThatThrownBy(() -> UnixZip.unpack(resource("chained-link.zip"), target.resolve("unpacked")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("x/evil.txt");
        assertThat(target.resolve("evil.txt")).doesNotExist();
    }

    @Test
    @DisplayName("Should reject a file that is not a zip archive")
    void shouldRejectNonZip() throws IOException {
        // given
        Path file = Files.writeString(target.resolve("not.zip"), "not a zip archive");

        // when / then
        assertThatThrownBy(() -> UnixZip.unixModes(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not a zip archive");
    }

    static Path resource(String name) throws URISyntaxException {
        return Path.of(UnixZipTest.class.getResource(name).toURI());
    }
}
