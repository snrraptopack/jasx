package jasx.web.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the JSON the server sends (batches, targets/web.md §8.4) into maps, lists, strings, numbers ({@code Long}
 * or {@code Double}), booleans and null. It trusts its input, which comes from the server under test.
 */
final class JsonReader {
    private final String text;
    private int pos;

    private JsonReader(String text) {
        this.text = text;
    }

    static Object read(String text) {
        JsonReader reader = new JsonReader(text);
        Object value = reader.value();
        reader.skipSpace();
        if (reader.pos != text.length()) {
            throw new IllegalArgumentException("unexpected text after the JSON value at " + reader.pos);
        }
        return value;
    }

    /** A JSON object, with unchecked access to its members (the server's batches have a known shape). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private Object value() {
        skipSpace();
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> objectValue();
            case '[' -> listValue();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> objectValue() {
        Map<String, Object> result = new LinkedHashMap<>();
        pos++; // {
        skipSpace();
        if (text.charAt(pos) == '}') {
            pos++;
            return result;
        }
        while (true) {
            skipSpace();
            String key = string();
            skipSpace();
            expect(':');
            result.put(key, value());
            skipSpace();
            if (text.charAt(pos) == ',') {
                pos++;
                continue;
            }
            expect('}');
            return result;
        }
    }

    private List<Object> listValue() {
        List<Object> result = new ArrayList<>();
        pos++; // [
        skipSpace();
        if (text.charAt(pos) == ']') {
            pos++;
            return result;
        }
        while (true) {
            result.add(value());
            skipSpace();
            if (text.charAt(pos) == ',') {
                pos++;
                continue;
            }
            expect(']');
            return result;
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
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
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
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

    private Object number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        String number = text.substring(start, pos);
        if (number.isEmpty()) {
            throw new IllegalArgumentException("unexpected character at " + start);
        }
        boolean integral = number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0;
        return integral ? (Object) Long.parseLong(number) : (Object) Double.parseDouble(number);
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw new IllegalArgumentException("unexpected text at " + pos);
        }
        pos += word.length();
        return value;
    }

    private void expect(char c) {
        if (text.charAt(pos) != c) {
            throw new IllegalArgumentException("expected '" + c + "' at " + pos);
        }
        pos++;
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }
}
