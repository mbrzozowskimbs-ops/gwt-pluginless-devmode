package cloud.rubibubi.gwt.pluginless.bridge;

import java.util.Arrays;
import java.util.List;

/**
 * Typed access to bridge protocol messages and commands, which are JSON arrays whose first element is a tag.
 */
final class Messages {

    private Messages() {
    }

    /**
     * Interprets a parsed JSON value as a message or command.
     *
     * @param json parsed JSON value
     * @return the array
     * @throws BridgeProtocolException when the value is not a non-empty array starting with a string tag
     */
    static List<?> message(Object json) throws BridgeProtocolException {
        List<?> message = list(json);
        if (message.isEmpty() || !(message.get(0) instanceof String)) {
            throw new BridgeProtocolException("Message must start with a string tag: " + json);
        }
        return message;
    }

    /**
     * Interprets a parsed JSON value as an array.
     *
     * @param json parsed JSON value
     * @return the array
     * @throws BridgeProtocolException when the value is not an array
     */
    static List<?> list(Object json) throws BridgeProtocolException {
        if (json instanceof List<?> list) {
            return list;
        }
        throw new BridgeProtocolException("Expected a JSON array, got: " + json);
    }

    static String tag(List<?> message) {
        return (String) message.get(0);
    }

    static Object element(List<?> message, int index) throws BridgeProtocolException {
        if (index >= message.size()) {
            throw new BridgeProtocolException("Missing element " + index + " in " + message);
        }
        return message.get(index);
    }

    static String string(List<?> message, int index) throws BridgeProtocolException {
        if (element(message, index) instanceof String string) {
            return string;
        }
        throw new BridgeProtocolException("Element " + index + " must be a string in " + message);
    }

    static int integer(List<?> message, int index) throws BridgeProtocolException {
        if (element(message, index) instanceof Long number
                && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
            return number.intValue();
        }
        throw new BridgeProtocolException("Element " + index + " must be an int in " + message);
    }

    static boolean bool(List<?> message, int index) throws BridgeProtocolException {
        if (element(message, index) instanceof Boolean bool) {
            return bool;
        }
        throw new BridgeProtocolException("Element " + index + " must be a boolean in " + message);
    }

    static List<?> list(List<?> message, int index) throws BridgeProtocolException {
        return list(element(message, index));
    }

    /**
     * Builds a command or message; unlike {@link List#of}, elements may be {@code null}.
     *
     * @param tag  command tag
     * @param args command arguments
     * @return fixed-size list
     */
    static List<Object> of(String tag, Object... args) {
        Object[] parts = new Object[args.length + 1];
        parts[0] = tag;
        System.arraycopy(args, 0, parts, 1, args.length);
        return Arrays.asList(parts);
    }
}
