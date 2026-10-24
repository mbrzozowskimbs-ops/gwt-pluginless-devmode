package cloud.rubibubi.gwt.pluginless.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON codec for the bridge wire format.
 * <p>
 * Parsing maps arrays to {@link List}, objects to {@link Map}, integral numbers to {@link Long},
 * other numbers to {@link Double}, plus {@link String}, {@link Boolean} and {@code null}.
 * Writing accepts the same types and any {@link Number}; non-finite doubles are rejected because
 * JSON cannot represent them.
 */
final class Json {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Json() {
    }

    /**
     * Parses a complete JSON document.
     *
     * @param text JSON text
     * @return parsed value
     * @throws IllegalArgumentException when the text is not valid JSON
     */
    static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("unexpected trailing content");
        }
        return value;
    }

    /**
     * Serializes a value to JSON text.
     *
     * @param value value composed of supported types
     * @return JSON text
     * @throws IllegalArgumentException for unsupported types or non-finite numbers
     */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out);
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            writeString(string, out);
        } else if (value instanceof Boolean bool) {
            out.append(bool.booleanValue());
        } else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("JSON cannot represent " + number);
            }
            out.append(number);
        } else if (value instanceof Number number) {
            out.append(number.longValue());
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeValue(list.get(i), out);
            }
            out.append(']');
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                writeValue(entry.getValue(), out);
            }
            out.append('}');
        } else {
            throw new IllegalArgumentException("Unsupported JSON type: " + value.getClass().getName());
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        // Unescaped runs are copied in one go: JSNI bodies run to hundreds of kilobytes
        int run = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String escape = switch (c) {
                case '"' -> "\\\"";
                case '\\' -> "\\\\";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                case '\b' -> "\\b";
                case '\f' -> "\\f";
                default -> null;
            };
            // Lone surrogates are escaped: written raw they would not survive UTF-8 encoding
            if (escape == null && c >= 0x20 && !isLoneSurrogate(value, i)) {
                continue;
            }
            out.append(value, run, i);
            if (escape != null) {
                out.append(escape);
            } else {
                out.append("\\u").append(HEX[c >> 12]).append(HEX[(c >> 8) & 0xf]).append(HEX[(c >> 4) & 0xf])
                        .append(HEX[c & 0xf]);
            }
            run = i + 1;
        }
        out.append(value, run, value.length());
        out.append('"');
    }

    private static boolean isLoneSurrogate(String value, int index) {
        char c = value.charAt(index);
        if (Character.isHighSurrogate(c)) {
            return index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return index == 0 || !Character.isHighSurrogate(value.charAt(index - 1));
        }
        return false;
    }

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        Object readValue() {
            skipWhitespace();
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '[' -> readArray();
                case '{' -> readObject();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield readNumber();
                    }
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        private List<Object> readArray() {
            pos++;
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private Map<String, Object> readObject() {
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected object key");
                }
                String key = readString();
                skipWhitespace();
                if (next() != ':') {
                    throw error("expected ':'");
                }
                map.put(key, readValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private String readString() {
            pos++;
            // Most strings (tags, JSNI) have no escapes and are taken as a substring
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '"') {
                    pos++;
                    return text.substring(start, pos - 1);
                }
                if (c == '\\' || c < 0x20) {
                    break;
                }
                pos++;
            }
            StringBuilder out = new StringBuilder().append(text, start, pos);
            while (true) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    char escaped = next();
                    switch (escaped) {
                        case '"', '\\', '/' -> out.append(escaped);
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw error("truncated unicode escape");
                            }
                            try {
                                out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException e) {
                                throw error("invalid unicode escape");
                            }
                            pos += 4;
                        }
                        default -> throw error("invalid escape '\\" + escaped + "'");
                    }
                } else if (c < 0x20) {
                    throw error("unescaped control character in string");
                } else {
                    out.append(c);
                }
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, pos)) {
                throw error("invalid literal");
            }
            pos += literal.length();
            return value;
        }

        private Number readNumber() {
            int start = pos;
            boolean integral = true;
            if (peek() == '-') {
                pos++;
            }
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    integral = false;
                    pos++;
                } else {
                    break;
                }
            }
            String number = text.substring(start, pos);
            try {
                if (integral) {
                    return Long.parseLong(number);
                }
                return Double.parseDouble(number);
            } catch (NumberFormatException e) {
                if (integral) {
                    // Integral but out of long range
                    return Double.parseDouble(number);
                }
                throw error("invalid number '" + number + "'");
            }
        }

        private char peek() {
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            return text.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Invalid JSON at position " + pos + ": " + message);
        }
    }
}
