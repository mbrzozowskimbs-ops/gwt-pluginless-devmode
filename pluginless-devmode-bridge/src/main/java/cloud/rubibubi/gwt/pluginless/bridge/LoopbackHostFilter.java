package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.IOException;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

/**
 * Rejects requests that do not address the bridge by a loopback name in the {@code Host} header (DNS rebinding,
 * see {@link OriginPolicy#isLoopbackHost(String)}). Installed on every context of the bridge's HTTP server.
 */
final class LoopbackHostFilter extends Filter {

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        if (OriginPolicy.isLoopbackHost(exchange.getRequestHeaders().getFirst("Host"))) {
            chain.doFilter(exchange);
            return;
        }
        try {
            HttpResponses.text(exchange, 403, "The bridge must be addressed as localhost, 127.0.0.1 or [::1]");
        } finally {
            exchange.close();
        }
    }

    @Override
    public String description() {
        return "Loopback Host header check";
    }
}
