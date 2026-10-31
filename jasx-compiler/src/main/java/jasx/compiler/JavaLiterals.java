package jasx.compiler;

import java.util.List;
import jasx.parser.JavaLexer;

/**
 * Helpers for Java literals, used for {@code @jasx.Default} values (semantics.md §5.2). Shared by the
 * compiler and the annotation processor so that both apply exactly the same rule.
 */
public final class JavaLiterals {
    private JavaLiterals() {}

    /**
     * Whether {@code source} is the source text of exactly one Java literal: a number (optionally negated
     * with {@code -}), {@code true} or {@code false}, or a character, string or text-block literal.
     *
     * <p>Examples: {@code "5"}, {@code "-1.5"}, {@code "true"}, {@code "'c'"} and {@code "\"text\""} are
     * literals; {@code "1 + 2"}, {@code "make()"} and {@code "null"} are not.
     */
    public static boolean isSingleLiteral(String source) {
        List<JavaLexer.Token> tokens = new JavaLexer(source).tokens(0, source.length());

        if (tokens.size() == 1) {
            JavaLexer.Token token = tokens.get(0);
            return isCompleteLiteral(token) || token.is("true") || token.is("false");
        }

        if (tokens.size() == 2) {
            boolean isNegativeNumber = tokens.get(0).is("-") && tokens.get(1).kind() == JavaLexer.Kind.NUMBER;
            return isNegativeNumber;
        }

        return false;
    }

    /** Whether a literal token is complete. The lexer also returns unterminated strings, which are not. */
    private static boolean isCompleteLiteral(JavaLexer.Token token) {
        String text = token.text();
        return switch (token.kind()) {
            case NUMBER -> true;
            // A string or character literal must end with the quote it starts with.
            case STRING, CHAR -> text.length() >= 2 && text.charAt(text.length() - 1) == text.charAt(0);
            case TEXT_BLOCK -> text.length() >= 6 && text.endsWith("\"\"\"");
            default -> false;
        };
    }

    /**
     * Returns the value of the Java string literal {@code raw} (including its quotes), with Unicode escapes
     * and escape sequences processed, or null if it is malformed.
     */
    static String decodeStringLiteral(String raw) {
        // JavaLexer reads characters with Unicode escapes already decoded, as javac does.
        JavaLexer chars = new JavaLexer(raw);
        StringBuilder value = new StringBuilder();

        int p = chars.next(0); // skip the opening quote
        while (p < raw.length()) {
            int c = chars.charAt(p);

            if (c == '"') {
                return value.toString(); // the closing quote
            }

            if (c != '\\') {
                value.append((char) c);
                p = chars.next(p);
                continue;
            }

            // An escape sequence: a backslash and at least one more character.
            p = chars.next(p);
            int escape = chars.charAt(p);
            if (escape >= '0' && escape <= '7') {
                p = appendOctalEscape(chars, p, value);
                continue;
            }
            String simple = simpleEscape(escape);
            if (simple == null) {
                return null; // an invalid escape
            }
            value.append(simple);
            p = chars.next(p);
        }

        return null; // no closing quote
    }

    /** The value of a one-character escape such as {@code \n}, or null if {@code escape} isn't one. */
    private static String simpleEscape(int escape) {
        return switch (escape) {
            case 'b' -> "\b";
            case 't' -> "\t";
            case 'n' -> "\n";
            case 'f' -> "\f";
            case 'r' -> "\r";
            case 's' -> " ";
            case '"' -> "\"";
            case '\'' -> "'";
            case '\\' -> "\\";
            default -> null;
        };
    }

    /**
     * Appends an octal escape ({@code \0} to {@code \377}) that starts at {@code p}, and returns the offset
     * after it. JLS §3.10.7: up to three digits if the first is 0–3, otherwise up to two.
     */
    private static int appendOctalEscape(JavaLexer chars, int p, StringBuilder value) {
        int maxDigits = chars.charAt(p) <= '3' ? 3 : 2;
        int code = 0;
        for (int digits = 0; digits < maxDigits; digits++) {
            int c = chars.charAt(p);
            if (c < '0' || c > '7') {
                break;
            }
            code = code * 8 + (c - '0');
            p = chars.next(p);
        }
        value.append((char) code);
        return p;
    }
}
