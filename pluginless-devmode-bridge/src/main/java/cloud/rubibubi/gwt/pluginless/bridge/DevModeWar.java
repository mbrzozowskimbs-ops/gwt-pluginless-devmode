package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The {@code -war} directory of a DevMode started with {@code -noserver}: DevMode links the module into
 * {@code <war>/<module>/} — the {@code <module>.devmode.js} carrying the Pluginless DevMode client, public resources and the
 * GWT-RPC serialization policies ({@code <strongName>.gwt.rpc}) that the application server does not have.
 * <p>
 * URL paths map to the directory after stripping a context prefix, e.g. with prefix {@code /myapp}
 * {@code /myapp/frontend/frontend.devmode.js} maps to {@code <war>/frontend/frontend.devmode.js}.
 * {@code WEB-INF} and {@code META-INF} are never served, as in a servlet container.
 */
final class DevModeWar {

    // Compared in upper case: on case-insensitive file systems web-inf is the same directory
    private static final Set<String> PRIVATE_DIRECTORIES = Set.of("WEB-INF", "META-INF");
    private static final Pattern POLICY_FILE = Pattern.compile("[0-9A-Fa-f]{32}\\.gwt\\.rpc");

    private final Path root;
    private final String contextPrefix;

    DevModeWar(Path root, String contextPrefix) {
        this.root = root.toAbsolutePath().normalize();
        this.contextPrefix = normalizePrefix(contextPrefix);
    }

    /**
     * Resolves a request path to a regular file inside the directory.
     *
     * @param urlPath decoded request path, e.g. {@code /myapp/frontend/frontend.devmode.js}
     * @return the file, or empty when the path is outside the context, escapes the directory, is under
     * {@code WEB-INF} or {@code META-INF} or does not exist
     */
    Optional<Path> resolve(String urlPath) {
        if (!urlPath.startsWith(contextPrefix)) {
            return Optional.empty();
        }
        String relative = urlPath.substring(contextPrefix.length());
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.isEmpty()) {
            return Optional.empty();
        }
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || isPrivate(root.relativize(file)) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(file);
    }

    /**
     * Finds a serialization policy file written by DevMode in any module directory.
     *
     * @param fileName {@code <strongName>.gwt.rpc}
     * @return the file, or empty when the name is invalid or no module directory contains it
     */
    Optional<Path> findPolicy(String fileName) {
        if (!POLICY_FILE.matcher(fileName).matches() || !Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> modules = Files.list(root)) {
            return modules.filter(Files::isDirectory)
                    .map(module -> module.resolve(fileName))
                    .filter(Files::isRegularFile)
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean isPrivate(Path relative) {
        return PRIVATE_DIRECTORIES.contains(relative.getName(0).toString().toUpperCase(Locale.ROOT));
    }

    private static String normalizePrefix(String prefix) {
        String normalized = prefix.startsWith("/") ? prefix : "/" + prefix;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.equals("/") ? "" : normalized;
    }
}
