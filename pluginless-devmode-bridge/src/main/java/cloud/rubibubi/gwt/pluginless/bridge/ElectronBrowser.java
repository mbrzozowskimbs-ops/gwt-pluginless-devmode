package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Starts the Pluginless DevMode debug browser ({@code pluginless-devmode-electron}) for {@link PluginlessDevMode}, in one of two ways:
 * <ul>
 *     <li>from the classpath ({@link #BROWSER_PROPERTY}): Electron from a {@code pluginless-devmode-electron-dist} jar and the
 *     app files from this jar, unpacked once by {@link ElectronDistribution},</li>
 *     <li>from a {@code pluginless-devmode-electron} directory ({@link #DIRECTORY_PROPERTY}) with the npm package installed,
 *     the executable taken from {@code node_modules/electron/path.txt}; for working on the browser itself.</li>
 * </ul>
 * Either way the browser starts the same way on Linux, macOS and Windows and needs no Node.js at run time.
 */
final class ElectronBrowser {

    /**
     * System property with the {@code pluginless-devmode-electron} directory; when set, {@link PluginlessDevMode} opens the
     * startup URL in the debug browser.
     */
    static final String DIRECTORY_PROPERTY = "pluginless.electron";

    /**
     * System property; {@code electron} makes {@link PluginlessDevMode} open the startup URL in the debug browser
     * installed from the classpath. {@link #DIRECTORY_PROPERTY} takes precedence.
     */
    static final String BROWSER_PROPERTY = "pluginless.browser";

    private ElectronBrowser() {
    }

    /**
     * Starts the debug browser; it is stopped when the JVM exits.
     *
     * @param directory {@code pluginless-devmode-electron} directory with {@code main.js} and {@code node_modules}
     * @param url       page to open
     * @return the browser process
     * @throws IOException when Electron is not installed in the directory or cannot be started
     */
    static Process start(Path directory, String url) throws IOException {
        Path app = directory.toAbsolutePath().normalize();
        return launch(executable(app), app, url);
    }

    /**
     * Installs the debug browser from the classpath unless it is installed already, then starts it; it is stopped
     * when the JVM exits.
     *
     * @param url page to open
     * @return the browser process
     * @throws IOException when there is no {@code pluginless-devmode-electron-dist} jar for the current platform on the
     *                     classpath or the browser cannot be started
     */
    static Process startFromClasspath(String url) throws IOException {
        ElectronDistribution.Installation installation =
                ElectronDistribution.install(ElectronBrowser.class.getClassLoader());
        return launch(installation.executable(), installation.app(), url);
    }

    private static Process launch(Path executable, Path app, String url) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command(executable, app, url, System.getProperty("os.name")))
                .inheritIO();
        // Would make Electron behave as plain Node.js
        builder.environment().remove("ELECTRON_RUN_AS_NODE");
        Process process = builder.start();
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroy, "pluginless-devmode-electron-stop"));
        return process;
    }

    /**
     * Resolves the platform specific Electron executable of the npm package.
     *
     * @param directory {@code pluginless-devmode-electron} directory
     * @return path of the executable
     * @throws IOException when the {@code electron} npm package is not installed
     */
    static Path executable(Path directory) throws IOException {
        Path electronPackage = directory.resolve("node_modules").resolve("electron");
        Path pathFile = electronPackage.resolve("path.txt");
        if (!Files.isRegularFile(pathFile)) {
            // IntelliJ leaves $NAME$ as is when the path variable is not defined
            String hint = directory.toString().matches(".*\\$\\w+\\$.*")
                    ? "; define the IntelliJ path variable in " + DIRECTORY_PROPERTY
                    + " (Settings | Appearance & Behavior | Path Variables)"
                    : "; run mvn install in pluginless-devmode-electron";
            throw new IOException("Electron is not installed in " + directory + hint);
        }
        return electronPackage.resolve("dist").resolve(Files.readString(pathFile).trim());
    }

    /**
     * Builds the command line of the debug browser.
     *
     * @param executable Electron executable
     * @param directory  {@code pluginless-devmode-electron} directory, the Electron app
     * @param url        page to open
     * @param osName     value of {@code os.name}
     * @return command and arguments
     */
    static List<String> command(Path executable, Path directory, String url, String osName) {
        List<String> command = new ArrayList<>(List.of(executable.toString(), directory.toString()));
        // Without a setuid chrome-sandbox or with AppArmor limits on user namespaces Electron exits at start on Linux
        if (osName != null && osName.toLowerCase(Locale.ROOT).startsWith("linux")) {
            command.add("--no-sandbox");
        }
        command.add(url);
        return command;
    }

    /**
     * Turns a DevMode {@code -startupUrl} into the page address for the bridge: a relative URL points to the DevMode
     * web server, and {@code gwt.codesvr} points to the bridge.
     * <p>
     * The bridge address is {@code 127.0.0.1}, because {@code localhost} may resolve to IPv6 first while the bridge
     * listens on IPv4.
     *
     * @param startupUrl DevMode {@code -startupUrl}, absolute or relative
     * @param serverPort port of the DevMode web server ({@code -port})
     * @param bridgePort port of the bridge
     * @return page address
     */
    static String pageUrl(String startupUrl, int serverPort, int bridgePort) {
        String url = URI.create(startupUrl).isAbsolute()
                ? startupUrl
                : "http://localhost:" + serverPort + "/" + startupUrl.replaceFirst("^/+", "");
        // devmode.js reads gwt.codesvr from location.search, so it goes before the fragment
        int hash = url.indexOf('#');
        String beforeFragment = hash < 0 ? url : url.substring(0, hash);
        String fragment = hash < 0 ? "" : url.substring(hash);
        return beforeFragment + (beforeFragment.contains("?") ? "&" : "?") + "gwt.codesvr=127.0.0.1:" + bridgePort
                + fragment;
    }
}
