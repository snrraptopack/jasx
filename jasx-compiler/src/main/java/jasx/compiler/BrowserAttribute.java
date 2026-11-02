package jasx.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The value of one browser attribute, {@code @click="..."} (semantics.md §11.2–§11.4): a call of a function of the
 * file's script with its payload, or inline code. Offsets are positions in the attribute's text; {@link Lowering}
 * maps them to the {@code .jasx} file.
 */
final class BrowserAttribute {
    private BrowserAttribute() {}

    /** What a browser attribute's value is. */
    sealed interface Parsed {}

    /** {@code toggleMenu({menu: $menu})}: the function's name and where it is, and the payload, or null. */
    record Call(String function, int functionStart, int functionEnd, Value payload) implements Parsed {}

    /** Any other JavaScript, with the refs it uses. */
    record Inline(String code, List<ScriptReader.RefUse> refs) implements Parsed {}

    /** A malformed payload (JASX2020): what is wrong, and where. */
    record Problem(String message, int start, int end) implements Parsed {}

    /** A value of a payload (§11.3). */
    sealed interface Value {}

    /** {@code $menu}. */
    record Ref(String name, int start, int end) implements Value {}

    /** {@code url}, {@code t.id()}: Java code. */
    record JavaName(String text, int start, int end) implements Value {}

    /** A string, number, {@code true}, {@code false} or {@code null}, as Java source. */
    record Literal(String java) implements Value {}

    /** {@code {a: 1, url}}: its keys and values, in order. */
    record ObjectValue(List<String> keys, List<Value> values) implements Value {}

    /** {@code [1, 2]}. */
    record ListValue(List<Value> values) implements Value {}

    private static final Set<String> LITERAL_WORDS = Set.of("true", "false", "null");

    /** Reads a browser attribute's value: a call, when it has the form {@code name(...)}, otherwise inline code. */
    static Parsed parse(String text) {
        int start = skipSpace(text, 0);
        int end = text.length();
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int nameEnd = identifierEnd(text, start);
        int open = skipSpace(text, nameEnd);
        boolean callForm = nameEnd > start && open < end && text.charAt(open) == '('
                && matchingParenthesis(text, open) == end - 1;
        if (!callForm) {
            return new Inline(text, ScriptReader.refs(text));
        }
        String function = text.substring(start, nameEnd);
        int argumentStart = skipSpace(text, open + 1);
        if (argumentStart == end - 1) {
            return new Call(function, start, nameEnd, null);
        }
        Reader reader = new Reader(text, argumentStart, end - 1);
        try {
            Value payload = reader.value();
            reader.skipSpace();
            if (reader.pos != reader.end) {
                throw reader.problem("the payload is one value; this is after its end");
            }
            return new Call(function, start, nameEnd, payload);
        } catch (ProblemException e) {
            return e.problem;
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static int skipSpace(String text, int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int identifierEnd(String text, int i) {
        if (i >= text.length() || !Character.isJavaIdentifierStart(text.charAt(i))) {
            return i;
        }
        i++;
        while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The index of the {@code )} that matches the {@code (} at {@code open}, strings skipped; -1 if none. */
    private static int matchingParenthesis(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = closingQuote(text, i);
                if (i < 0) {
                    return -1;
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int closingQuote(String text, int start) {
        char quote = text.charAt(start);
        for (int i = start + 1; i < text.length(); i++) {
            if (text.charAt(i) == '\\') {
                i++;
            } else if (text.charAt(i) == quote) {
                return i;
            }
        }
        return -1;
    }

    /** Thrown inside the reader to stop at the first problem. */
    private static final class ProblemException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Problem problem;

        ProblemException(Problem problem) {
            super(problem.message(), null, false, false);
            this.problem = problem;
        }
    }

    /** A recursive descent over the payload notation of §11.3, between {@code pos} and {@code end}. */
    private static final class Reader {
        private final String text;
        private final int end;
        private int pos;

        Reader(String text, int pos, int end) {
            this.text = text;
            this.pos = pos;
            this.end = end;
        }

        ProblemException problem(String message) {
            return new ProblemException(new Problem(message, pos, Math.min(end, pos + 1)));
        }

        void skipSpace() {
            while (pos < end && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        boolean at(char c) {
            skipSpace();
            return pos < end && text.charAt(pos) == c;
        }

        void expect(char c) {
            if (!at(c)) {
                throw problem("expected '" + c + "'");
            }
            pos++;
        }

        Value value() {
            skipSpace();
            if (pos >= end) {
                throw problem("a value is missing");
            }
            char c = text.charAt(pos);
            if (c == '$') {
                return ref();
            }
            if (c == '\'') {
                return new Literal(Lowering.javaString(string()));
            }
            if (c == '"' || c == '`') {
                throw problem("strings in a payload are written in single quotes: 'text'");
            }
            if (c == '-' || Character.isDigit(c)) {
                return number();
            }
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return list();
            }
            if (Character.isJavaIdentifierStart(c)) {
                return javaNameOrWord();
            }
            throw problem("this is not a value: payloads hold refs ($name), Java names, literals, objects and lists");
        }

        private Ref ref() {
            int start = pos;
            pos++;
            int nameEnd = identifierEnd(text, pos);
            if (nameEnd == pos) {
                throw problem("a ref is '$' and a name");
            }
            pos = nameEnd;
            return new Ref(text.substring(start + 1, nameEnd), start, nameEnd);
        }

        /** A single-quoted string, with {@code \'} and {@code \\}; returns its value. */
        private String string() {
            StringBuilder value = new StringBuilder();
            pos++;
            while (pos < end && text.charAt(pos) != '\'') {
                char c = text.charAt(pos);
                if (c == '\\' && pos + 1 < end && (text.charAt(pos + 1) == '\'' || text.charAt(pos + 1) == '\\')) {
                    value.append(text.charAt(pos + 1));
                    pos += 2;
                } else {
                    value.append(c);
                    pos++;
                }
            }
            if (pos >= end) {
                throw problem("the string has no closing quote");
            }
            pos++;
            return value.toString();
        }

        private Literal number() {
            int start = pos;
            if (text.charAt(pos) == '-') {
                pos++;
            }
            int digits = pos;
            while (pos < end && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos == digits) {
                throw problem("a number has digits after '-'");
            }
            if (pos < end && text.charAt(pos) == '.') {
                pos++;
                int decimals = pos;
                while (pos < end && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
                if (pos == decimals) {
                    throw problem("a number has digits after '.'");
                }
            }
            return new Literal(text.substring(start, pos));
        }

        private ObjectValue object() {
            pos++;
            List<String> keys = new ArrayList<>();
            List<Value> values = new ArrayList<>();
            while (!at('}')) {
                skipSpace();
                if (pos >= end) {
                    throw problem("the object has no closing '}'");
                }
                if (text.charAt(pos) == '\'') {
                    keys.add(string());
                    expect(':');
                    values.add(value());
                } else {
                    int nameStart = pos;
                    int nameEnd = identifierEnd(text, pos);
                    if (nameEnd == pos) {
                        throw problem("a key is a name or a string in single quotes");
                    }
                    String name = text.substring(nameStart, nameEnd);
                    pos = nameEnd;
                    keys.add(name);
                    if (at(':')) {
                        pos++;
                        values.add(value());
                    } else {
                        // {url}: shorthand for {url: url}, a Java name
                        values.add(new JavaName(name, nameStart, nameEnd));
                    }
                }
                if (!at(',')) {
                    break;
                }
                pos++;
            }
            expect('}');
            return new ObjectValue(keys, values);
        }

        private ListValue list() {
            pos++;
            List<Value> values = new ArrayList<>();
            while (!at(']')) {
                values.add(value());
                if (!at(',')) {
                    break;
                }
                pos++;
            }
            expect(']');
            return new ListValue(values);
        }

        /** {@code true}, {@code false}, {@code null}, or a Java name such as {@code user.address().city()}. */
        private Value javaNameOrWord() {
            int start = pos;
            while (true) {
                int nameEnd = identifierEnd(text, pos);
                if (nameEnd == pos) {
                    throw problem("a name is expected after '.'");
                }
                pos = nameEnd;
                if (pos + 1 < end && text.charAt(pos) == '(' && text.charAt(skipSpaceFrom(pos + 1)) == ')') {
                    pos = skipSpaceFrom(pos + 1) + 1; // a method call without arguments
                }
                if (pos < end && text.charAt(pos) == '.') {
                    pos++;
                    continue;
                }
                break;
            }
            String written = text.substring(start, pos);
            if (LITERAL_WORDS.contains(written)) {
                return new Literal(written);
            }
            if (pos < end && text.charAt(pos) == '(') {
                throw problem("a Java name in a payload calls methods without arguments only; use a local variable");
            }
            return new JavaName(written, start, pos);
        }

        private int skipSpaceFrom(int i) {
            while (i < end && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            return Math.min(i, end - 1);
        }
    }
}
