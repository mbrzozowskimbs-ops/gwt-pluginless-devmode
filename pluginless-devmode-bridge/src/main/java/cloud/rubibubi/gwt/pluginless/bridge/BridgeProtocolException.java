package cloud.rubibubi.gwt.pluginless.bridge;

/**
 * Signals a message that violates the bridge protocol, either from the browser or from the code server.
 * The session it occurred in is no longer usable.
 */
class BridgeProtocolException extends Exception {

    BridgeProtocolException(String message) {
        super(message);
    }

    BridgeProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
