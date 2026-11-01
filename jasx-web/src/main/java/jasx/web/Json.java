package jasx.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The little JSON the web protocol needs (targets/web.md §8): reading events, writing batches. Objects are
 * read as {@code LinkedHashMap}, arrays as {@code ArrayList}, numbers as {@code Long} or {@code Double}. The
 * runtime has no third-party dependencies, so it carries its own.
 *
 * <p>Events come from the network, so reading must fail only with {@link IllegalArgumentException}, which the
 * server answers with 400: every index is checked, and nesting is limited so a hostile body can't overflow the
 * stack.
 */
final class Json {
    /** Events nest two levels (an object of field lists); this leaves room without risking the stack. */
    private static final int MAX_DEPTH = 32;

    private final String text;
    private int pos;
    private int depth;

    private Json(String text) {
        this.text = text;
    }

    // ---------------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------------

    /** Writes maps, lists, strings, numbers, booleans and null. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String s -> string(s, out);
            case Boolean b -> out.append(b);
            case Number n -> out.append(n);
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    string(String.valueOf(entry.getKey()), out);
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
            default -> string(String.valueOf(value), out);
        }
    }

    /**
     * A JSON string. Besides quotes, backslashes and control characters, {@code <} is escaped, so that the
     * JSON can't close a script element it might be embedded in.
     */
    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
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

    /** Reads a JSON object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Json reader = new Json(text);
        Object value = reader.value();
        reader.skipWhitespace();
        if (reader.pos != text.length() || !(value instanceof Map)) {
            throw new IllegalArgumentException("not a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private Object value() {
        skipWhitespace();
        if (pos >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> nested(this::object);
            case '[' -> nested(this::array);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Object nested(java.util.function.Supplier<Object> container) {
        if (++depth > MAX_DEPTH) {
            throw error("nested too deeply");
        }
        try {
            return container.get();
        } finally {
            depth--;
        }
    }

    private Map<String, Object> object() {
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
            map.put(key, value());
            skipWhitespace();
            if (peek() == ',') {
                pos++;
                continue;
            }
            expect('}');
            return map;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(value());
            skipWhitespace();
            if (peek() == ',') {
                pos++;
                continue;
            }
            expect(']');
            return list;
        }
    }

    private String string() {
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
            if (pos >= text.length()) {
                throw error("unfinished escape"); // a request cut off after a backslash
            }
            char escaped = text.charAt(pos++);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> out.append(hexCharacter());
                case '"', '\\', '/' -> out.append(escaped);
                default -> throw error("unknown escape");
            }
        }
    }

    /** The four hex digits after a backslash and {@code u}, checked so that a cut-off request is a JSON error. */
    private char hexCharacter() {
        if (pos + 4 > text.length()) {
            throw error("unfinished escape");
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(pos++), 16);
            if (digit < 0) {
                throw error("bad hex digit in escape");
            }
            code = code * 16 + digit;
        }
        return (char) code;
    }

    private Object number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        String number = text.substring(start, pos);
        if (number.isEmpty()) {
            throw error("unexpected character");
        }
        boolean isInteger = number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0;
        return isInteger ? (Object) Long.parseLong(number) : (Object) Double.parseDouble(number);
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("unexpected word");
        }
        pos += word.length();
        return value;
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("bad JSON at " + pos + ": " + what);
    }
}
