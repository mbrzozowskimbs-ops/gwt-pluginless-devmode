package cloud.rubibubi.gwt.pluginless.bridge;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which web pages may drive the bridge.
 * <p>
 * The bridge executes arbitrary code in the DevMode JVM and in the browser, so it accepts requests only from pages
 * whose {@code Origin} is allowed (by default: any page served from a loopback host) and only when the request
 * targets the bridge by a loopback name in the {@code Host} header, which defeats DNS rebinding.
 */
final class OriginPolicy {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final List<String> allowedOrigins;

    private OriginPolicy(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * Allows pages served from {@code localhost}, {@code 127.0.0.1} or {@code [::1]} on any port and scheme.
     *
     * @return loopback policy
     */
    static OriginPolicy loopback() {
        return new OriginPolicy(List.of());
    }

    /**
     * Allows exactly the given origins, e.g. {@code http://localhost:8080}.
     *
     * @param origins allowed origins; empty means {@link #loopback()}
     * @return explicit policy
     */
    static OriginPolicy of(List<String> origins) {
        return new OriginPolicy(origins.stream().map(OriginPolicy::normalize).toList());
    }

    /**
     * Checks the {@code Origin} request header.
     *
     * @param origin header value, may be {@code null}
     * @return {@code true} when a page from this origin may use the bridge
     */
    boolean allowsOrigin(String origin) {
        if (origin == null || origin.isBlank() || "null".equals(origin)) {
            return false;
        }
        if (!allowedOrigins.isEmpty()) {
            return allowedOrigins.contains(normalize(origin));
        }
        try {
            String host = URI.create(origin).getHost();
            return host != null && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Checks the {@code Host} request header.
     *
     * @param hostHeader header value including an optional port, may be {@code null}
     * @return {@code true} when the request addresses the bridge by a loopback name
     */
    static boolean isLoopbackHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end > 0 && LOOPBACK_HOSTS.contains(host.substring(0, end + 1));
        }
        int colon = host.indexOf(':');
        return LOOPBACK_HOSTS.contains(colon >= 0 ? host.substring(0, colon) : host);
    }

    private static String normalize(String origin) {
        String trimmed = origin.trim().toLowerCase(Locale.ROOT);
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @Override
    public String toString() {
        return allowedOrigins.isEmpty() ? "any loopback origin" : String.join(", ", allowedOrigins);
    }
}
