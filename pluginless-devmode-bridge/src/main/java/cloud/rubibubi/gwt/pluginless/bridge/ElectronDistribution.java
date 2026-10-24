package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * The debug browser installed from the classpath, so that no Node.js or npm is needed: the Electron build of a
 * {@code pluginless-devmode-electron-dist} jar (one per platform) and the {@code pluginless-devmode-electron} app files bundled in this
 * jar. Both are unpacked once into a cache directory ({@code ~/.pluginless-devmode} by default):
 * <ul>
 *     <li>{@code electron/<version>-<platform>} — Electron, unpacked under a file lock and marked complete at the
 *     end, so an interrupted unpack is redone and concurrent DevMode starts unpack it once,</li>
 *     <li>{@code app/<content hash>} — {@code main.js} and the other app files; the hash keeps every bridge
 *     version on its own files.</li>
 * </ul>
 */
final class ElectronDistribution {

    /**
     * System property with the cache directory.
     */
    static final String CACHE_PROPERTY = "pluginless.cacheDir";

    static final String PROPERTIES_RESOURCE = "META-INF/pluginless-devmode/electron.properties";
    static final String APP_RESOURCES = "META-INF/pluginless-devmode/app/";
    /**
     * Files of the {@code pluginless-devmode-electron} app bundled under {@link #APP_RESOURCES}.
     */
    static final List<String> APP_FILES = List.of("package.json", "main.js", "preload.js", "toolbar-preload.js",
            "toolbar.html", "toolbar.css", "toolbar.js");

    private static final String COMPLETE_MARKER = ".complete";
    private static final System.Logger LOG = System.getLogger(ElectronDistribution.class.getName());

    /**
     * Installed debug browser.
     *
     * @param executable Electron executable
     * @param app        directory of the Electron app ({@code main.js})
     */
    record Installation(Path executable, Path app) {
    }

    private ElectronDistribution() {
    }

    /**
     * Installs the debug browser for the current platform into the cache directory, unless it is there already.
     *
     * @param loader class loader with a {@code pluginless-devmode-electron-dist} jar and the app resources
     * @return installed browser
     * @throws IOException when there is no Electron build for the current platform or it cannot be unpacked
     */
    static Installation install(ClassLoader loader) throws IOException {
        String cacheDir = System.getProperty(CACHE_PROPERTY);
        Path cache = cacheDir != null ? Path.of(cacheDir) : Path.of(System.getProperty("user.home"), ".pluginless-devmode");
        return install(loader, cache, platform(System.getProperty("os.name"), System.getProperty("os.arch")));
    }

    /**
     * Installs the debug browser for the given platform into the cache directory, unless it is there already.
     *
     * @param loader   class loader with a {@code pluginless-devmode-electron-dist} jar and the app resources
     * @param cache    cache directory
     * @param platform Electron platform, e.g. {@code darwin-arm64}
     * @return installed browser
     * @throws IOException when there is no Electron build for the platform or it cannot be unpacked
     */
    static Installation install(ClassLoader loader, Path cache, String platform) throws IOException {
        URL propertiesUrl = findDistribution(loader, platform);
        Properties properties = load(propertiesUrl);
        String version = properties.getProperty("version");
        Path electron = cache.resolve("electron").resolve(version + "-" + platform);
        unpackOnce(sibling(propertiesUrl, "electron.zip"), electron, version, platform);
        return new Installation(electron.resolve(properties.getProperty("executable")),
                installApp(loader, cache.resolve("app")));
    }

    /**
     * Electron platform name of the JVM's operating system and architecture.
     *
     * @param osName value of {@code os.name}
     * @param osArch value of {@code os.arch}
     * @return platform, e.g. {@code darwin-arm64}, {@code win32-x64}, {@code linux-x64}
     * @throws IllegalStateException for an operating system or architecture Electron is not built for
     */
    static String platform(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String system = os.startsWith("mac") || os.startsWith("darwin") ? "darwin"
                : os.startsWith("windows") ? "win32"
                : os.startsWith("linux") ? "linux"
                : null;
        String arch = switch (osArch.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "arm64";
            case "amd64", "x86_64" -> "x64";
            default -> null;
        };
        if (system == null || arch == null) {
            throw new IllegalStateException("No Electron build for " + osName + " " + osArch);
        }
        return system + "-" + arch;
    }

    private static URL findDistribution(ClassLoader loader, String platform) throws IOException {
        List<String> found = new ArrayList<>();
        for (URL url : Collections.list(loader.getResources(PROPERTIES_RESOURCE))) {
            String candidate = load(url).getProperty("platform");
            if (platform.equals(candidate)) {
                return url;
            }
            found.add(candidate);
        }
        throw new IOException("No pluginless-devmode-electron-dist for " + platform + " on the classpath"
                + (found.isEmpty() ? "" : " (found: " + String.join(", ", found) + ")"));
    }

    private static void unpackOnce(URL zip, Path target, String version, String platform) throws IOException {
        Path marker = target.resolve(COMPLETE_MARKER);
        if (Files.exists(marker)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Path lockFile = target.resolveSibling(target.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            // Another JVM may have unpacked it while this one waited for the lock
            if (Files.exists(marker)) {
                return;
            }
            LOG.log(System.Logger.Level.INFO, "Unpacking Electron {0} ({1}) into {2}", version, platform, target);
            deleteRecursively(target);
            // ZipFile needs a file, the zip is inside the dist jar
            Path archive = Files.createTempFile(target.getParent(), "electron-", ".zip");
            try {
                try (InputStream in = zip.openStream()) {
                    Files.copy(in, archive, StandardCopyOption.REPLACE_EXISTING);
                }
                UnixZip.unpack(archive, target);
                Files.createFile(marker);
            } finally {
                Files.deleteIfExists(archive);
            }
        }
    }

    private static Path installApp(ClassLoader loader, Path appRoot) throws IOException {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        MessageDigest digest = sha256();
        for (String file : APP_FILES) {
            try (InputStream in = loader.getResourceAsStream(APP_RESOURCES + file)) {
                if (in == null) {
                    throw new IOException("Missing debug browser file " + APP_RESOURCES + file);
                }
                byte[] content = in.readAllBytes();
                digest.update(file.getBytes(StandardCharsets.UTF_8));
                digest.update(content);
                contents.put(file, content);
            }
        }
        Path app = appRoot.resolve(HexFormat.of().formatHex(digest.digest(), 0, 8));
        if (Files.exists(app.resolve(COMPLETE_MARKER))) {
            return app;
        }
        // Concurrent writers write the same content
        Files.createDirectories(app);
        for (Map.Entry<String, byte[]> file : contents.entrySet()) {
            Files.write(app.resolve(file.getKey()), file.getValue());
        }
        Files.write(app.resolve(COMPLETE_MARKER), new byte[0]);
        return app;
    }

    private static Properties load(URL url) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = url.openStream()) {
            properties.load(in);
        }
        return properties;
    }

    // Resolves within the same jar: jar: URLs are opaque to URI.resolve
    @SuppressWarnings("deprecation")
    private static URL sibling(URL url, String name) throws MalformedURLException {
        return new URL(url, name);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
