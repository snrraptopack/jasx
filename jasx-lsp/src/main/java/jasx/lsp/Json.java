package jasx.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for the messages of the Language Server Protocol.
 *
 * <p>Values are plain Java objects: {@code Map<String, Object>} (insertion-ordered) for objects,
 * {@code List<Object>} for arrays, {@code String}, {@code Long} for integers, {@code Double} for other
 * numbers, {@code Boolean}, and {@code null}. The server only needs these, so there is no third-party
 * JSON library.
 */
final class Json {
    private Json() {}

    // ---------------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------------

    /** {@code value} as JSON text, without whitespace. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String s -> writeString(s, out);
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Number n -> out.append(n.doubleValue());
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(String.valueOf(entry.getKey()), out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(list.get(i), out);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("not a JSON value: " + value.getClass());
        }
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\%s%04x", "u", (int) c)); // a control character
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ---------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------

    /** Parses JSON text. Throws {@link IllegalArgumentException} if it is not valid JSON. */
    static Object parse(String text) {
        Reader reader = new Reader(text);
        reader.skipWhitespace();
        Object value = reader.value();
        reader.skipWhitespace();
        if (reader.pos != text.length()) {
            throw reader.error("unexpected text after the value");
        }
        return value;
    }

    /** A recursive-descent reader over the text. */
    private static final class Reader {
        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        Object value() {
            if (pos >= text.length()) {
                throw error("unexpected end of input");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, value());
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                } else {
                    expect('}');
                    return map;
                }
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value());
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                } else {
                    expect(']');
                    return list;
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw error("unclosed string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> out.append(escaped); // \" \\ \/
                }
            }
        }

        Object number() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String number = text.substring(start, pos);
            if (number.isEmpty()) {
                throw error("unexpected character '" + text.charAt(start) + "'");
            }
            boolean isInteger = number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0;
            return isInteger ? (Object) Long.parseLong(number) : (Object) Double.parseDouble(number);
        }

        Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) {
                throw error("expected " + word);
            }
            pos += word.length();
            return value;
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        void expect(char c) {
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("invalid JSON at offset " + pos + ": " + message);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Reading values out of parsed JSON
    // ---------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    static String string(Object value) {
        return value instanceof String s ? s : null;
    }

    static int integer(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
