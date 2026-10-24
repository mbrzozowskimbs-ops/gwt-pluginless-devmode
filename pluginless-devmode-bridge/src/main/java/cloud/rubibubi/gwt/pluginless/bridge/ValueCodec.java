package cloud.rubibubi.gwt.pluginless.bridge;

import java.util.Arrays;
import java.util.List;

import com.google.gwt.dev.shell.BrowserChannel.JavaObjectRef;
import com.google.gwt.dev.shell.BrowserChannel.JsObjectRef;
import com.google.gwt.dev.shell.BrowserChannel.Value;

/**
 * Converts {@link Value BrowserChannel values} to and from their JSON representation in the bridge protocol.
 * <p>
 * Encoding:
 * <ul>
 *     <li>{@code null}, booleans, numbers and strings map to the same JSON values,</li>
 *     <li>{@code ["u"]} is JavaScript {@code undefined},</li>
 *     <li>{@code ["d", "NaN" | "Infinity" | "-Infinity"]} is a non-finite double,</li>
 *     <li>{@code ["j", id]} is a reference to a Java object held by the code server,</li>
 *     <li>{@code ["o", id]} is a reference to a JavaScript object held by the browser.</li>
 * </ul>
 * Integral numbers within the {@code int} range decode to INT values, all other numbers to DOUBLE,
 * which mirrors what the original browser plugins sent.
 */
final class ValueCodec {

    static final String UNDEFINED = "u";
    static final String SPECIAL_DOUBLE = "d";
    static final String JAVA_OBJECT = "j";
    static final String JS_OBJECT = "o";

    private static final List<Object> UNDEFINED_JSON = List.of(UNDEFINED);
    private static final List<Object> NAN_JSON = List.of(SPECIAL_DOUBLE, "NaN");
    private static final List<Object> INFINITY_JSON = List.of(SPECIAL_DOUBLE, "Infinity");
    private static final List<Object> NEGATIVE_INFINITY_JSON = List.of(SPECIAL_DOUBLE, "-Infinity");

    private ValueCodec() {
    }

    /**
     * Decodes a JSON value into a BrowserChannel value.
     *
     * @param json value produced by {@link Json#parse(String)}
     * @return decoded value
     * @throws BridgeProtocolException when the JSON does not describe a value
     */
    static Value decode(Object json) throws BridgeProtocolException {
        Value value = new Value();
        if (json == null) {
            value.setNull();
        } else if (json instanceof Boolean bool) {
            value.setBoolean(bool);
        } else if (json instanceof Long number && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
            value.setInt(number.intValue());
        } else if (json instanceof Number number) {
            value.setDouble(number.doubleValue());
        } else if (json instanceof String string) {
            value.setString(string);
        } else if (json instanceof List<?> tagged && !tagged.isEmpty() && tagged.get(0) instanceof String tag) {
            decodeTagged(tag, tagged, value);
        } else {
            throw new BridgeProtocolException("Not a value: " + json);
        }
        return value;
    }

    private static void decodeTagged(String tag, List<?> tagged, Value value) throws BridgeProtocolException {
        switch (tag) {
            case UNDEFINED -> value.setUndefined();
            case SPECIAL_DOUBLE -> value.setDouble(parseSpecialDouble(Messages.string(tagged, 1)));
            case JAVA_OBJECT -> value.setJavaObject(new JavaObjectRef(Messages.integer(tagged, 1)));
            case JS_OBJECT -> value.setJsObject(new JsObjectRef(Messages.integer(tagged, 1)));
            default -> throw new BridgeProtocolException("Unknown value tag: " + tag);
        }
    }

    private static double parseSpecialDouble(String text) throws BridgeProtocolException {
        return switch (text) {
            case "NaN" -> Double.NaN;
            case "Infinity" -> Double.POSITIVE_INFINITY;
            case "-Infinity" -> Double.NEGATIVE_INFINITY;
            default -> throw new BridgeProtocolException("Unknown special double: " + text);
        };
    }

    /**
     * Decodes a JSON array of values.
     *
     * @param json JSON array
     * @return decoded values in the same order
     * @throws BridgeProtocolException when an element does not describe a value
     */
    static Value[] decodeAll(List<?> json) throws BridgeProtocolException {
        Value[] values = new Value[json.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = decode(json.get(i));
        }
        return values;
    }

    /**
     * Encodes a BrowserChannel value as JSON.
     *
     * @param value value received from the code server
     * @return JSON representation accepted by {@link Json#write(Object)}
     */
    static Object encode(Value value) {
        if (value.isNull()) {
            return null;
        }
        if (value.isUndefined()) {
            return UNDEFINED_JSON;
        }
        if (value.isBoolean()) {
            return value.getBoolean();
        }
        if (value.isInt()) {
            return value.getInt();
        }
        if (value.isShort()) {
            return (int) value.getShort();
        }
        if (value.isByte()) {
            return (int) value.getByte();
        }
        if (value.isChar()) {
            return (int) value.getChar();
        }
        if (value.isDouble()) {
            double number = value.getDouble();
            return Double.isFinite(number) ? number : specialDouble(number);
        }
        if (value.isString()) {
            return value.getString();
        }
        if (value.isJavaObject()) {
            return List.of(JAVA_OBJECT, value.getJavaObject().getRefid());
        }
        if (value.isJsObject()) {
            return List.of(JS_OBJECT, value.getJsObject().getRefid());
        }
        throw new IllegalArgumentException("Unsupported value type: " + value.getType());
    }

    private static List<Object> specialDouble(double number) {
        if (Double.isNaN(number)) {
            return NAN_JSON;
        }
        return number > 0 ? INFINITY_JSON : NEGATIVE_INFINITY_JSON;
    }

    /**
     * Encodes BrowserChannel values as a JSON array.
     *
     * @param values values received from the code server
     * @return list of JSON representations
     */
    static List<Object> encodeAll(Value[] values) {
        return Arrays.stream(values).map(ValueCodec::encode).toList();
    }

    /**
     * Creates an INT value.
     *
     * @param number value
     * @return BrowserChannel value
     */
    static Value intValue(int number) {
        Value value = new Value();
        value.setInt(number);
        return value;
    }

    /**
     * Creates a STRING value.
     *
     * @param text value
     * @return BrowserChannel value
     */
    static Value stringValue(String text) {
        Value value = new Value();
        value.setString(text);
        return value;
    }
}
