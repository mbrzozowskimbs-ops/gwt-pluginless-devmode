package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.dev.DevMode;

/**
 * Drop-in replacement for {@link DevMode} as the main class: starts the Pluginless DevMode bridge in the same JVM, then runs
 * DevMode with the unchanged arguments. One run configuration, one process; the bridge stops with DevMode.
 * <p>
 * The bridge takes the code server port from DevMode once it is bound ({@code -codeServerPort}, default 9997, also
 * {@code auto}) and the DevMode output directory from {@code -war} (default {@code war}). Everything else comes from
 * system properties:
 * <ul>
 *     <li>{@code pluginless.port} — bridge port, the value of {@code gwt.codesvr} (default 9876),</li>
 *     <li>{@code pluginless.proxy} — application server to forward page requests to, e.g. {@code http://localhost:8080}
 *     (only for DevMode with {@code -noserver}),</li>
 *     <li>{@code pluginless.warContext} — URL prefix of the {@code -war} directory, e.g. {@code /myapp}
 *     (default {@code /}),</li>
 *     <li>{@code pluginless.allowOrigin} — comma separated page origins (default: any loopback origin),</li>
 *     <li>{@code pluginless.trace} — {@code true} prints every protocol message,</li>
 *     <li>{@code pluginless.statsFile} — file for round trip statistics (default {@code pluginless-devmode-stats.log} in
 *     {@code -logdir}),</li>
 *     <li>{@code pluginless.browser} — {@code electron} opens the first {@code -startupUrl} in the debug browser once the
 *     modules are linked, installed from a {@code pluginless-devmode-electron-dist} jar on the classpath into
 *     {@code pluginless.cacheDir} (default {@code ~/.pluginless-devmode}, see {@link ElectronDistribution}),</li>
 *     <li>{@code pluginless.electron} — the same with the browser from a {@code pluginless-devmode-electron} directory with its
 *     npm package installed (see {@link ElectronBrowser}).</li>
 * </ul>
 * As with plain DevMode, client sources, module XML files and other client resources must be on the classpath.
 * <p>
 * Unlike plain DevMode, it runs client code compiled with {@code -sourceLevel} 11 or newer: the class rewriter of
 * every module goes through {@link JsoNestRepair}, which keeps private access inside nests of JavaScriptObject
 * subtypes working. Client code also gets the JDK classes of platform modules such as {@code java.sql}, which plain
 * DevMode cannot load on Java 9+ (see {@link PlatformJavaClasses}).
 * <p>
 * When debugging, set breakpoints to suspend only their thread: with "suspend all" the bridge threads stop as well,
 * and evaluating an expression that touches the DOM waits for them forever.
 */
public final class PluginlessDevMode extends DevMode {

    private final BridgeServer bridge;

    private PluginlessDevMode(BridgeServer bridge) {
        this.bridge = bridge;
        browserHost = new NestRepairingBrowserWidgetHost(browserHost);
        PlatformJavaClasses.install();
    }

    // Hides DevMode.main, so it cannot declare IOException
    public static void main(String[] args) {
        GwtVersionCheck.warnOnMismatch();
        defaultStatsFile(args);
        BridgeOptions options = BridgeOptions.forDevMode(args, System.getProperties());
        BridgeServer server;
        try {
            server = BridgeServer.start(options);
            BridgeServer.printBanner(server, options);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start the Pluginless DevMode bridge", e);
        }

        // Same as DevMode.main, which always exits to stop threads started by generators
        PluginlessDevMode devMode = new PluginlessDevMode(server);
        if (new ArgProcessor(devMode.options).processArgs(args)) {
            devMode.run();
            System.exit(0);
        }
        System.exit(-1);
    }

    // The actual port also for -codeServerPort auto, which DevMode picks only now
    @Override
    protected void ensureCodeServerListener() {
        super.ensureCodeServerListener();
        bridge.codeServerPort(listener.getSocketPort());
    }

    @Override
    protected boolean doSlowStartup() {
        boolean linked = super.doSlowStartup();
        if (linked) {
            startDebugBrowser();
        }
        return linked;
    }

    private void startDebugBrowser() {
        String directory = System.getProperty(ElectronBrowser.DIRECTORY_PROPERTY);
        boolean fromDirectory = directory != null && !directory.isBlank();
        if (!fromDirectory && !"electron".equalsIgnoreCase(System.getProperty(ElectronBrowser.BROWSER_PROPERTY))) {
            return;
        }
        List<String> startupUrls = options.getStartupURLs();
        if (startupUrls.isEmpty()) {
            getTopLogger().log(TreeLogger.WARN, "The Pluginless DevMode debug browser needs a -startupUrl");
            return;
        }
        String url = ElectronBrowser.pageUrl(startupUrls.get(0), options.getPort(), bridge.port());
        try {
            if (fromDirectory) {
                ElectronBrowser.start(Path.of(directory), url);
            } else {
                ElectronBrowser.startFromClasspath(url);
            }
            getTopLogger().log(TreeLogger.INFO, "Pluginless DevMode debug browser: " + url);
        } catch (IOException e) {
            getTopLogger().log(TreeLogger.ERROR, "Cannot start the Pluginless DevMode debug browser", e);
        }
    }

    /**
     * Writes round trip statistics ({@link BridgeStats}) next to the DevMode logs unless {@code pluginless.statsFile} is
     * set.
     */
    private static void defaultStatsFile(String[] args) {
        if (System.getProperty(BridgeStats.STATS_FILE_PROPERTY) != null) {
            return;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if ("-logdir".equals(args[i])) {
                System.setProperty(BridgeStats.STATS_FILE_PROPERTY,
                        Path.of(args[i + 1], "pluginless-devmode-stats.log").toString());
            }
        }
    }
}
