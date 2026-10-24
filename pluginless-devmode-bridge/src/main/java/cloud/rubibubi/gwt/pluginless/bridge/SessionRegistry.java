package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live browser sessions by the id each page generates for itself.
 * A session is created by a batch containing {@code connect} and removed once it closes.
 */
final class SessionRegistry {

    private static final System.Logger LOG = System.getLogger(SessionRegistry.class.getName());

    private final String codeServerHost;
    // 0 until DevMode has bound its code server (-codeServerPort auto)
    private volatile int codeServerPort;
    private final BridgeTrace trace;
    private final Map<String, BridgeSession> sessions = new ConcurrentHashMap<>();

    SessionRegistry(String codeServerHost, int codeServerPort, BridgeTrace trace) {
        this.codeServerHost = codeServerHost;
        this.codeServerPort = codeServerPort;
        this.trace = trace;
    }

    /**
     * Routes a batch of browser messages to its session, opening the session on {@code connect}.
     *
     * @param sessionId session id from the request path
     * @param messages  browser messages
     * @return commands for the browser
     * @throws IOException             when the code server connection fails
     * @throws BridgeProtocolException when the session is unknown or a message is invalid
     */
    List<Object> handle(String sessionId, List<?> messages) throws IOException, BridgeProtocolException {
        BridgeSession session = containsConnect(messages) ? open(sessionId) : sessions.get(sessionId);
        if (session == null) {
            throw new BridgeProtocolException("Unknown session " + sessionId
                    + " (the bridge or DevMode was restarted; reload the page)");
        }
        try {
            return session.handle(messages);
        } finally {
            if (session.isClosed() && sessions.remove(sessionId, session)) {
                LOG.log(System.Logger.Level.INFO, "Session {0} closed", sessionId);
            }
        }
    }

    /**
     * Adds the time the HTTP handler spent on one request of the session to its statistics.
     *
     * @param sessionId session id from the request path
     * @param nanos     time from reading the request to writing the response
     */
    void recordHttp(String sessionId, long nanos) {
        BridgeSession session = sessions.get(sessionId);
        if (session != null) {
            session.recordHttp(nanos);
        }
    }

    /**
     * Sets the code server port for sessions opened from now on.
     *
     * @param port port DevMode has bound its code server to
     */
    void codeServerPort(int port) {
        codeServerPort = port;
    }

    void closeAll() {
        sessions.values().forEach(BridgeSession::close);
        sessions.clear();
    }

    private BridgeSession open(String sessionId) throws IOException {
        int codeServerPort = this.codeServerPort;
        if (codeServerPort == 0) {
            throw new IOException("DevMode has not started its code server yet");
        }
        BridgeSession session = BridgeSession.open(sessionId, codeServerHost, codeServerPort, trace);
        BridgeSession previous = sessions.put(sessionId, session);
        if (previous != null) {
            previous.close();
        }
        LOG.log(System.Logger.Level.INFO, "Session {0} connected to code server {1}:{2,number,#}",
                sessionId, codeServerHost, codeServerPort);
        return session;
    }

    private static boolean containsConnect(List<?> messages) {
        return messages.stream()
                .anyMatch(message -> message instanceof List<?> list && !list.isEmpty() && "connect".equals(list.get(0)));
    }
}
