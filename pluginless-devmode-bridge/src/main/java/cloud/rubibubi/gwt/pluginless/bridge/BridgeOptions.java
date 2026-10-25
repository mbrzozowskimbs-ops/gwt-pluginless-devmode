package cloud.rubibubi.gwt.pluginless.bridge;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Command line options of {@link BridgeServer}.
 *
 * @param port           HTTP port the browser connects to (the value of {@code gwt.codesvr})
 * @param codeServerHost host of the DevMode code server
 * @param codeServerPort port of the DevMode code server ({@code -codeServerPort})
 * @param originPolicy   pages allowed to use the bridge
 * @param trace          whether to print every protocol message
 * @param appServer      application server to forward page requests to, or {@code null}
 * @param war            DevMode {@code -war} directory to serve module files and RPC policies from, or {@code null}
 * @param warContext     URL path prefix that maps to the {@code -war} directory, e.g. {@code /myapp}
 */
record BridgeOptions(int port, String codeServerHost, int codeServerPort, OriginPolicy originPolicy, boolean trace,
                     URI appServer, Path war, String warContext) {

    static final int DEFAULT_PORT = 9876;
    static final String DEFAULT_CODE_SERVER_HOST = "127.0.0.1";
    static final int DEFAULT_CODE_SERVER_PORT = 9997;

    static final String USAGE = """
            Usage: BridgeServer [options]
              --port <port>               HTTP port for the browser, used as gwt.codesvr (default 9876)
              --code-server <host:port>   GWT DevMode code server (default 127.0.0.1:9997)
              --allow-origin <origin>     page origin allowed to connect, repeatable
                                          (default: any http(s)://localhost|127.0.0.1|[::1] origin)
              --proxy <url>               forward page requests to this application server, e.g. http://localhost:8080
              --war <dir>                 DevMode -war directory: serves <module>.devmode.js and /policies/*.gwt.rpc
              --war-context <path>        URL prefix of the -war directory, e.g. /myapp (default /)
              --trace                     print every protocol message
              --help                      show this help
            """;

    /**
     * Options with only the bridge endpoint (no proxy, no -war directory).
     */
    BridgeOptions(int port, String codeServerHost, int codeServerPort, OriginPolicy originPolicy, boolean trace) {
        this(port, codeServerHost, codeServerPort, originPolicy, trace, null, null, "/");
    }

    /**
     * Parses command line arguments.
     *
     * @param args command line arguments
     * @return parsed options
     * @throws IllegalArgumentException for unknown or malformed arguments
     */
    static BridgeOptions parse(String... args) {
        int port = DEFAULT_PORT;
        String codeServerHost = DEFAULT_CODE_SERVER_HOST;
        int codeServerPort = DEFAULT_CODE_SERVER_PORT;
        List<String> origins = new ArrayList<>();
        boolean trace = false;
        URI appServer = null;
        Path war = null;
        String warContext = "/";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = parsePort(requireValue(args, ++i));
                case "--code-server" -> {
                    String address = requireValue(args, ++i);
                    int colon = address.lastIndexOf(':');
                    if (colon <= 0) {
                        throw new IllegalArgumentException("--code-server must be host:port, got " + address);
                    }
                    codeServerHost = address.substring(0, colon);
                    codeServerPort = parsePort(address.substring(colon + 1));
                }
                case "--allow-origin" -> origins.add(requireValue(args, ++i));
                case "--proxy" -> appServer = parseAppServer(requireValue(args, ++i));
                case "--war" -> war = Path.of(requireValue(args, ++i));
                case "--war-context" -> warContext = requireValue(args, ++i);
                case "--trace" -> trace = true;
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        return new BridgeOptions(port, codeServerHost, codeServerPort, OriginPolicy.of(origins), trace, appServer, war,
                warContext);
    }

    /**
     * Derives bridge options from DevMode arguments ({@code -codeServerPort}, {@code -war}) and
     * {@code pluginless.*} system properties; see {@link PluginlessDevMode}. For {@code -codeServerPort auto} the code server
     * port is {@code 0}, as in DevMode, until {@link PluginlessDevMode} sets the port DevMode has bound.
     *
     * @param devModeArgs DevMode command line arguments
     * @param properties  system properties
     * @return bridge options
     * @throws IllegalArgumentException for malformed values
     */
    static BridgeOptions forDevMode(String[] devModeArgs, Properties properties) {
        int codeServerPort = DEFAULT_CODE_SERVER_PORT;
        Path war = Path.of("war");
        for (int i = 0; i < devModeArgs.length - 1; i++) {
            switch (devModeArgs[i]) {
                case "-codeServerPort" -> codeServerPort = "auto".equals(devModeArgs[i + 1])
                        ? 0 : parsePort(devModeArgs[i + 1]);
                case "-war" -> war = Path.of(devModeArgs[i + 1]);
                default -> {
                    // Other DevMode arguments do not concern the bridge
                }
            }
        }
        String port = properties.getProperty("pluginless.port");
        String proxy = properties.getProperty("pluginless.proxy");
        String origins = properties.getProperty("pluginless.allowOrigin");
        OriginPolicy policy = origins == null || origins.isBlank()
                ? OriginPolicy.loopback()
                : OriginPolicy.of(Arrays.stream(origins.split(",")).map(String::trim).toList());
        return new BridgeOptions(port == null ? DEFAULT_PORT : parsePort(port),
                DEFAULT_CODE_SERVER_HOST, codeServerPort, policy,
                Boolean.parseBoolean(properties.getProperty("pluginless.trace")),
                proxy == null || proxy.isBlank() ? null : parseAppServer(proxy),
                war, properties.getProperty("pluginless.warContext", "/"));
    }

    private static URI parseAppServer(String url) {
        URI uri = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("--proxy must be an http:// URL, got " + url);
        }
        // AppServerHandler.forwardTarget would ignore them; fail instead of dropping them silently
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("--proxy must not have a query or fragment, got " + url);
        }
        return uri;
    }

    private static String requireValue(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + args[index - 1]);
        }
        return args[index];
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text);
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("Port out of range: " + text);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port: " + text, e);
        }
    }
}
