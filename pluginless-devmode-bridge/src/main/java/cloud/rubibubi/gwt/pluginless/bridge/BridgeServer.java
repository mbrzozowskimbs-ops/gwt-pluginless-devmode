package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Pluginless DevMode bridge: lets a plugin-less browser drive GWT Classic DevMode.
 * <p>
 * The page loads {@code <module>.devmode.js} with the Pluginless DevMode client (see the {@code pluginless-devmode-linker} module) and
 * points it at this server with {@code ?gwt.codesvr=127.0.0.1:<port>}. Each page gets its own TCP connection to the
 * DevMode code server, over which the bridge speaks the native BrowserChannel protocol.
 * <p>
 * With {@code --proxy} and {@code --war} the bridge also fronts an application server for DevMode started with
 * {@code -noserver} (see {@link AppServerHandler}).
 * <p>
 * The server listens on the loopback interface only.
 */
public final class BridgeServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(BridgeServer.class.getName());
    private static final LoopbackHostFilter LOOPBACK_HOST = new LoopbackHostFilter();

    private final HttpServer httpServer;
    private final ExecutorService executor;
    private final SessionRegistry sessions;

    private BridgeServer(HttpServer httpServer, ExecutorService executor, SessionRegistry sessions) {
        this.httpServer = httpServer;
        this.executor = executor;
        this.sessions = sessions;
    }

    public static void main(String[] args) throws IOException {
        BridgeOptions options;
        try {
            if (args.length == 1 && "--help".equals(args[0])) {
                System.out.print(BridgeOptions.USAGE);
                return;
            }
            options = BridgeOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.print(BridgeOptions.USAGE);
            System.exit(2);
            return;
        }

        BridgeServer server = start(options);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "pluginless-devmode-shutdown"));
        printBanner(server, options);
    }

    /**
     * Prints where the bridge listens and how to open a page through it.
     *
     * @param server  running bridge
     * @param options its options
     */
    static void printBanner(BridgeServer server, BridgeOptions options) {
        System.out.printf("Pluginless DevMode bridge listening on http://127.0.0.1:%d -> code server %s:%s (origins: %s)%n",
                server.port(), options.codeServerHost(),
                options.codeServerPort() == 0 ? "auto" : String.valueOf(options.codeServerPort()),
                options.originPolicy());
        if (options.appServer() != null) {
            System.out.printf("Proxying http://localhost:%d/ -> %s%n", server.port(), options.appServer());
        }
        if (options.war() != null) {
            String context = options.warContext().endsWith("/") ? options.warContext() : options.warContext() + "/";
            System.out.printf("Serving DevMode output from %s at %s and /policies/%n",
                    options.war().toAbsolutePath(), context);
        }
        System.out.printf("Open your page with ?gwt.codesvr=localhost:%d%n", server.port());
    }

    /**
     * Starts the bridge.
     *
     * @param options bridge options; port {@code 0} picks a free port
     * @return running server
     * @throws IOException when the port cannot be bound
     */
    static BridgeServer start(BridgeOptions options) throws IOException {
        BridgeTrace trace = options.trace() ? BridgeTrace.to(System.out) : BridgeTrace.disabled();
        SessionRegistry sessions = new SessionRegistry(options.codeServerHost(), options.codeServerPort(), trace);

        HttpServer httpServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), options.port()), 0);
        // One thread per in-flight request; a blocked browser holds at most one request per page
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "pluginless-devmode-http");
            thread.setDaemon(true);
            return thread;
        });
        httpServer.setExecutor(executor);
        createContext(httpServer, BridgeHttpHandler.CONTEXT, new BridgeHttpHandler(sessions, options.originPolicy()));
        if (options.appServer() != null || options.war() != null) {
            DevModeWar war = options.war() == null ? null : new DevModeWar(options.war(), options.warContext());
            createContext(httpServer, "/", new AppServerHandler(war, options.appServer()));
        }
        httpServer.start();
        return new BridgeServer(httpServer, executor, sessions);
    }

    // Every context gets the Host check, so none can be reached through DNS rebinding
    private static void createContext(HttpServer httpServer, String path, HttpHandler handler) {
        httpServer.createContext(path, handler).getFilters().add(LOOPBACK_HOST);
    }

    /**
     * Sets the code server port for sessions opened from now on, for a bridge started before DevMode bound its code
     * server ({@code -codeServerPort auto}).
     *
     * @param port code server port
     */
    void codeServerPort(int port) {
        sessions.codeServerPort(port);
    }

    /**
     * @return the bound HTTP port
     */
    int port() {
        return httpServer.getAddress().getPort();
    }

    /**
     * Closes all sessions and stops the HTTP server.
     */
    @Override
    public void close() {
        LOG.log(System.Logger.Level.INFO, "Stopping Pluginless DevMode bridge");
        sessions.closeAll();
        httpServer.stop(0);
        executor.shutdownNow();
    }
}
