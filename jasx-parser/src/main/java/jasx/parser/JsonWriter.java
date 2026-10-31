package jasx.parser;

/**
 * A minimal pretty-printing JSON writer. The output format (2-space indent, one array element per
 * line, empty containers as {@code []} / {@code {}}) matches {@code JSON.stringify(value, null, 2)},
 * so dumped ASTs diff cleanly against the conformance fixtures.
 */
final class JsonWriter {
    private final StringBuilder out = new StringBuilder();
    private int depth;
    private boolean first = true;
    private boolean afterName;

    JsonWriter beginObject() {
        prefix();
        out.append('{');
        depth++;
        first = true;
        return this;
    }

    JsonWriter endObject() {
        return close('}');
    }

    JsonWriter beginArray() {
        prefix();
        out.append('[');
        depth++;
        first = true;
        return this;
    }

    JsonWriter endArray() {
        return close(']');
    }

    JsonWriter name(String name) {
        prefix();
        string(name);
        out.append(": ");
        afterName = true;
        return this;
    }

    JsonWriter value(String value) {
        prefix();
        if (value == null) {
            out.append("null");
        } else {
            string(value);
        }
        return this;
    }

    JsonWriter value(int value) {
        prefix();
        out.append(value);
        return this;
    }

    JsonWriter value(boolean value) {
        prefix();
        out.append(value);
        return this;
    }

    JsonWriter nullValue() {
        prefix();
        out.append("null");
        return this;
    }

    @Override
    public String toString() {
        return out.toString();
    }

    private void prefix() {
        if (afterName) {
            afterName = false;
            return;
        }
        if (depth > 0) {
            if (!first) {
                out.append(',');
            }
            out.append('\n');
            indent();
        }
        first = false;
    }

    private JsonWriter close(char c) {
        depth--;
        if (!first) {
            out.append('\n');
            indent();
        }
        first = false;
        out.append(c);
        return this;
    }

    private void indent() {
        out.append("  ".repeat(depth));
    }

    private void string(String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
