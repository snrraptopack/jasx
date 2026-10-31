package jasx.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizes Java source as far as JASX needs (grammar.md §3): it decodes Unicode escapes the way javac
 * does, skips comments and whitespace, and recognizes identifiers, literals and operators. It never
 * builds a syntax tree. Unterminated string and character literals end at the end of their line.
 */
public final class JavaLexer {
    public enum Kind {
        IDENTIFIER,
        NUMBER,
        STRING,
        TEXT_BLOCK,
        CHAR,
        /** An operator, a separator, or any other single character. */
        OPERATOR
    }

    /**
     * A token covering {@code [start, end)}. {@code text} is the decoded text for identifiers and
     * operators, and the raw source text for literals.
     */
    public record Token(Kind kind, String text, int start, int end) {
        /** Whether this is the identifier, keyword or operator {@code s}. */
        public boolean is(String s) {
            return (kind == Kind.IDENTIFIER || kind == Kind.OPERATOR) && text.equals(s);
        }

        public boolean isLiteral() {
            return kind == Kind.NUMBER || kind == Kind.STRING || kind == Kind.TEXT_BLOCK || kind == Kind.CHAR;
        }
    }

    /** Multi-character operators, longest first. */
    private static final String[] OPERATORS = {
        ">>>=", "<<=", ">>=", ">>>", "...",
        "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", ">=",
        "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>",
    };

    private final String src;
    private final int len;

    public JavaLexer(String src) {
        this.src = src;
        this.len = src.length();
    }

    public String source() {
        return src;
    }

    /** All tokens that start in {@code [start, end)}. */
    public List<Token> tokens(int start, int end) {
        List<Token> tokens = new ArrayList<>();
        int p = skipTrivia(start);
        while (p < end && p < len) {
            Token t = lex(p);
            tokens.add(t);
            p = skipTrivia(t.end());
        }
        return tokens;
    }

    /** Lexes the token that starts at {@code p}, which must be a significant character before the end. */
    public Token lex(int p) {
        int start = p;
        int c = charAt(p);
        if (Character.isJavaIdentifierStart(c)) {
            StringBuilder id = new StringBuilder();
            while (p < len && Character.isJavaIdentifierPart(charAt(p))) {
                id.append((char) charAt(p));
                p = next(p);
            }
            return new Token(Kind.IDENTIFIER, id.toString(), start, p);
        }
        if (isAsciiDigit(c) || (c == '.' && isAsciiDigit(charAt(next(p))))) {
            int last = 0;
            while (p < len) {
                int d = charAt(p);
                boolean exponentSign = (d == '+' || d == '-')
                        && (last == 'e' || last == 'E' || last == 'p' || last == 'P');
                if (!(Character.isLetterOrDigit(d) || d == '_' || d == '.' || exponentSign)) {
                    break;
                }
                last = d;
                p = next(p);
            }
            return new Token(Kind.NUMBER, src.substring(start, p), start, p);
        }
        if (c == '"') {
            if (charAt(next(p)) == '"' && charAt(next(next(p))) == '"') {
                p = skipTextBlock(p);
                return new Token(Kind.TEXT_BLOCK, src.substring(start, p), start, p);
            }
            p = skipQuoted(p, '"');
            return new Token(Kind.STRING, src.substring(start, p), start, p);
        }
        if (c == '\'') {
            p = skipQuoted(p, '\'');
            return new Token(Kind.CHAR, src.substring(start, p), start, p);
        }
        for (String op : OPERATORS) {
            int end = match(p, op);
            if (end >= 0) {
                return new Token(Kind.OPERATOR, op, start, end);
            }
        }
        return new Token(Kind.OPERATOR, String.valueOf((char) c), start, next(p));
    }

    /** Skips whitespace and comments from {@code p}; returns the offset of the next significant character. */
    public int skipTrivia(int p) {
        while (p < len) {
            int c = charAt(p);
            if (c == ' ' || c == '\t' || c == '\f' || c == '\n' || c == '\r') {
                p = next(p);
            } else if (c == '/' && charAt(next(p)) == '/') {
                while (p < len && charAt(p) != '\n' && charAt(p) != '\r') {
                    p = next(p);
                }
            } else if (c == '/' && charAt(next(p)) == '*') {
                p = next(next(p));
                while (p < len && !(charAt(p) == '*' && charAt(next(p)) == '/')) {
                    p = next(p);
                }
                p = p < len ? next(next(p)) : len;
            } else {
                break;
            }
        }
        return p;
    }

    /** The character at {@code p}, with Unicode escapes decoded (JLS §3.3), or -1 at the end. */
    public int charAt(int p) {
        if (p >= len) {
            return -1;
        }
        char c = src.charAt(p);
        if (c == '\\') {
            int end = unicodeEscapeEnd(p);
            if (end > 0) {
                return Integer.parseInt(src, end - 4, end, 16);
            }
        }
        return c;
    }

    /** The offset of the character after the one at {@code p}. */
    public int next(int p) {
        if (p >= len) {
            return len;
        }
        if (src.charAt(p) == '\\') {
            int end = unicodeEscapeEnd(p);
            if (end > 0) {
                return end;
            }
        }
        return p + 1;
    }

    private int skipQuoted(int p, char quote) {
        p = next(p);
        while (p < len) {
            int c = charAt(p);
            if (c == '\\') {
                p = next(next(p));
            } else if (c == quote) {
                return next(p);
            } else if (c == '\n' || c == '\r') {
                return p;
            } else {
                p = next(p);
            }
        }
        return p;
    }

    private int skipTextBlock(int p) {
        p = next(next(next(p)));
        while (p < len) {
            int c = charAt(p);
            if (c == '\\') {
                p = next(next(p));
            } else if (c == '"' && charAt(next(p)) == '"' && charAt(next(next(p))) == '"') {
                return next(next(next(p)));
            } else {
                p = next(p);
            }
        }
        return p;
    }

    /** Returns the offset after {@code text} if the characters at {@code p} spell it, else -1. */
    private int match(int p, String text) {
        for (int i = 0; i < text.length(); i++) {
            if (charAt(p) != text.charAt(i)) {
                return -1;
            }
            p = next(p);
        }
        return p;
    }

    /**
     * If a Unicode escape starts at {@code p}, returns the offset after it, otherwise -1. A backslash
     * starts an escape only if it is preceded by an even number of contiguous backslashes.
     */
    private int unicodeEscapeEnd(int p) {
        int q = p + 1;
        if (q >= len || src.charAt(q) != 'u') {
            return -1;
        }
        int backslashes = 0;
        for (int k = p - 1; k >= 0 && src.charAt(k) == '\\'; k--) {
            backslashes++;
        }
        if (backslashes % 2 != 0) {
            return -1;
        }
        while (q < len && src.charAt(q) == 'u') {
            q++;
        }
        if (q + 4 > len) {
            return -1;
        }
        for (int i = q; i < q + 4; i++) {
            char h = src.charAt(i);
            if (!(isAsciiDigit(h) || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F'))) {
                return -1;
            }
        }
        return q + 4;
    }

    private static boolean isAsciiDigit(int c) {
        return c >= '0' && c <= '9';
    }
}
