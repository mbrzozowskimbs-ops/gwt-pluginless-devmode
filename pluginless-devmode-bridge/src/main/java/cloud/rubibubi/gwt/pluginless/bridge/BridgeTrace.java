package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.PrintStream;
import java.util.List;

/**
 * Optional protocol trace: prints every browser message and every response to a stream.
 * JSNI bodies are shortened, since a module load sends hundreds of kilobytes of them.
 */
final class BridgeTrace {

    private static final int MAX_LENGTH = 300;

    private final PrintStream out;

    private BridgeTrace(PrintStream out) {
        this.out = out;
    }

    static BridgeTrace to(PrintStream out) {
        return new BridgeTrace(out);
    }

    static BridgeTrace disabled() {
        return new BridgeTrace(null);
    }

    void request(String sessionId, List<?> message) {
        print(sessionId, "<- ", message);
    }

    void response(String sessionId, List<?> commands) {
        print(sessionId, "-> ", commands);
    }

    private void print(String sessionId, String direction, Object json) {
        if (out == null) {
            return;
        }
        String text = Json.write(json);
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH) + "... (" + text.length() + " chars)";
        }
        out.println("[bridge " + shortId(sessionId) + "] " + direction + text);
    }

    static String shortId(String sessionId) {
        return sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
    }
}
