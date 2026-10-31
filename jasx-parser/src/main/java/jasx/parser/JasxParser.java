package jasx.parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jasx.parser.ast.Ast.AttributeName;
import jasx.parser.ast.Ast.AttributeValue;
import jasx.parser.ast.Ast.Child;
import jasx.parser.ast.Ast.ElementName;
import jasx.parser.ast.Ast.Expression;
import jasx.parser.ast.Ast.JasxAttribute;
import jasx.parser.ast.Ast.JasxClosingElement;
import jasx.parser.ast.Ast.JasxClosingFragment;
import jasx.parser.ast.Ast.JasxCompilationUnit;
import jasx.parser.ast.Ast.JasxElement;
import jasx.parser.ast.Ast.JasxEmptyExpression;
import jasx.parser.ast.Ast.JasxExpressionContainer;
import jasx.parser.ast.Ast.JasxFragment;
import jasx.parser.ast.Ast.JasxIdentifier;
import jasx.parser.ast.Ast.JasxNamespacedName;
import jasx.parser.ast.Ast.JasxOpeningElement;
import jasx.parser.ast.Ast.JasxOpeningFragment;
import jasx.parser.ast.Ast.JasxQualifiedName;
import jasx.parser.ast.Ast.JasxStringLiteral;
import jasx.parser.ast.Ast.JasxText;
import jasx.parser.ast.Ast.JavaCode;
import jasx.parser.ast.Ast.JavaExpression;
import jasx.parser.ast.Ast.Markup;
import jasx.parser.ast.Ast.Segment;

/**
 * The JASX parser (jasx-spec/grammar.md). Java source is only tokenized, never parsed: the parser
 * finds markup with the detection rule of grammar.md §4, parses it, and reports syntax errors
 * (JASX1xxx). After an error it recovers and continues, so one run can report several errors.
 */
public final class JasxParser {
    public static final String SPEC_VERSION = "1.0";

    private final String src;
    private final int len;
    private final JavaLexer lexer;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private LineMap lines;
    /** Only the innermost construct reports reaching end of input; enclosing ones stay quiet. */
    private boolean eofReported;
    private int pos;

    private JasxParser(String src) {
        this.src = src;
        this.len = src.length();
        this.lexer = new JavaLexer(src);
    }

    public static ParseResult parse(String source, String sourceFile) {
        JasxParser parser = new JasxParser(source);
        JavaScan scan = parser.scanJava(0, false);
        JasxCompilationUnit unit = new JasxCompilationUnit(0, source.length(), sourceFile, scan.segments());
        return new ParseResult(unit, List.copyOf(parser.diagnostics));
    }

    // ---------------------------------------------------------------------------------------------
    // Java mode (grammar.md §3, §4)
    // ---------------------------------------------------------------------------------------------

    /** A significant Java token, reduced to what markup detection needs. */
    private record Tok(String text, boolean arrayInit, boolean yieldStatement) {
        static Tok of(String text) {
            return new Tok(text, false, false);
        }
    }

    private static final Tok NO_TOKEN = Tok.of("");
    /** The markup-mode '{' that opens an expression container. */
    private static final Tok CONTAINER_OPEN = Tok.of("{jasx");
    /** A complete markup expression, which is an operand. */
    private static final Tok MARKUP_OPERAND = Tok.of("<jasx>");
    /** A string, character, text-block or number literal. */
    private static final Tok LITERAL = Tok.of("0");

    private record Bracket(char kind, boolean arrayInit) {}

    /** The result of scanning Java mode: segments, the end offset, and whether it was closed by '}'. */
    private record JavaScan(List<Segment> segments, int end, boolean empty, boolean closed) {}

    /** The set S of grammar.md §4.1, except '{' and 'yield', which depend on context. */
    private static final Set<String> MARKUP_INTRODUCERS = Set.of(
            "return", "(", "[", ",",
            "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=",
            "->", "?", ":", "&&", "||",
            CONTAINER_OPEN.text());

    private static boolean introducesMarkup(Tok t) {
        return switch (t.text()) {
            case "{" -> t.arrayInit();
            case "yield" -> t.yieldStatement();
            default -> MARKUP_INTRODUCERS.contains(t.text());
        };
    }

    /**
     * Scans Java mode from {@code from}. At top level it runs to end of input; inside an expression
     * container it stops before the '}' that closes the container.
     */
    private JavaScan scanJava(int from, boolean inContainer) {
        List<Segment> segments = new ArrayList<>();
        Deque<Bracket> brackets = new ArrayDeque<>();
        Tok prev = inContainer ? CONTAINER_OPEN : NO_TOKEN;
        boolean empty = true;
        int chunk = from;
        pos = from;
        while (true) {
            pos = lexer.skipTrivia(pos);
            int c = lexer.charAt(pos);
            if (c < 0) {
                addJavaCode(segments, chunk, len);
                return new JavaScan(segments, len, empty, false);
            }
            if (inContainer && c == '}' && brackets.isEmpty()) {
                addJavaCode(segments, chunk, pos);
                return new JavaScan(segments, pos, empty, true);
            }
            empty = false;
            if (src.charAt(pos) == '<' && introducesMarkup(prev)) {
                addJavaCode(segments, chunk, pos);
                Markup markup = parseMarkup(pos);
                segments.add(markup);
                pos = markup.end();
                chunk = pos;
                prev = MARKUP_OPERAND;
            } else {
                prev = lexJavaToken(brackets, prev);
            }
        }
    }

    private void addJavaCode(List<Segment> segments, int start, int end) {
        if (end > start) {
            segments.add(new JavaCode(start, end, src.substring(start, end)));
        }
    }

    /** Lexes one significant token at {@code pos}, tracks brackets, and advances past it. */
    private Tok lexJavaToken(Deque<Bracket> brackets, Tok prev) {
        JavaLexer.Token t = lexer.lex(pos);
        pos = t.end();
        String text = t.text();
        if (t.kind() == JavaLexer.Kind.IDENTIFIER) {
            return text.equals("yield") ? new Tok(text, false, beginsStatement(prev, brackets)) : Tok.of(text);
        }
        if (t.isLiteral()) {
            return LITERAL;
        }
        switch (text) {
            case "{" -> {
                String p = prev.text();
                boolean arrayInit = p.equals("=") || p.equals("]")
                        || ((p.equals("{") || p.equals(",")) && !brackets.isEmpty() && brackets.peek().arrayInit());
                brackets.push(new Bracket('{', arrayInit));
                return new Tok("{", arrayInit, false);
            }
            case "(", "[" -> brackets.push(new Bracket(text.charAt(0), false));
            case ")", "]", "}" -> {
                if (!brackets.isEmpty()) {
                    brackets.pop();
                }
            }
            default -> {}
        }
        return Tok.of(text);
    }

    /** Whether a 'yield' preceded by {@code prev} begins a statement (grammar.md §4.3). */
    private static boolean beginsStatement(Tok prev, Deque<Bracket> brackets) {
        return switch (prev.text()) {
            case "", "}", "else", "do", ")", ":" -> true;
            case ";" -> brackets.isEmpty() || brackets.peek().kind() != '(';
            case "{" -> !prev.arrayInit();
            default -> false;
        };
    }


    // ---------------------------------------------------------------------------------------------
    // Markup mode (grammar.md §5, §6)
    // ---------------------------------------------------------------------------------------------

    /** Parses an element or fragment starting at the '<' at {@code lt}. Leaves {@code pos} at its end. */
    private Markup parseMarkup(int lt) {
        pos = lt + 1;
        skipWhitespace();
        if (pos < len && src.charAt(pos) == '>') {
            pos++;
            return parseFragment(new JasxOpeningFragment(lt, pos));
        }
        ElementName name = parseElementName();
        if (name == null) {
            if (pos >= len) {
                reportEof("JASX1002", "unclosed element", lt, len);
            } else {
                unexpected(false);
            }
            JasxIdentifier missing = new JasxIdentifier(pos, pos, "");
            JasxOpeningElement opening = new JasxOpeningElement(lt, pos, missing, List.of(), true);
            return new JasxElement(lt, pos, opening, List.of(), null);
        }
        List<JasxAttribute> attributes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (true) {
            skipWhitespace();
            if (pos >= len) {
                reportEof("JASX1002", "unclosed element <" + nameText(name) + ">", lt, len);
                JasxOpeningElement opening = new JasxOpeningElement(lt, len, name, attributes, false);
                return new JasxElement(lt, len, opening, List.of(), null);
            }
            char c = src.charAt(pos);
            if (c == '/' && charAt(pos + 1) == '>') {
                pos += 2;
                JasxOpeningElement opening = new JasxOpeningElement(lt, pos, name, attributes, true);
                return new JasxElement(lt, pos, opening, List.of(), null);
            }
            if (c == '>') {
                pos++;
                return parseElementBody(new JasxOpeningElement(lt, pos, name, attributes, false));
            }
            if (c == '{') {
                parseBracedAttribute();
            } else if (isIdentifierStart(pos)) {
                JasxAttribute attribute = parseAttribute();
                String key = attributeNameText(attribute.name());
                if (!seen.add(key)) {
                    error("JASX1004", "duplicate attribute '" + key + "'", attribute.start(), attribute.end());
                }
                attributes.add(attribute);
            } else {
                unexpected(true);
            }
        }
    }

    private JasxElement parseElementBody(JasxOpeningElement opening) {
        List<Child> children = new ArrayList<>();
        while (true) {
            if (pos >= len) {
                reportEof("JASX1002", "unclosed element <" + nameText(opening.name()) + ">",
                        opening.start(), opening.end());
                return new JasxElement(opening.start(), len, opening, children, null);
            }
            if (atClosingTag()) {
                JasxClosingElement closing = parseClosingElement(opening);
                return new JasxElement(opening.start(), closing.end(), opening, children, closing);
            }
            parseChild(children);
        }
    }

    private JasxFragment parseFragment(JasxOpeningFragment opening) {
        List<Child> children = new ArrayList<>();
        while (true) {
            if (pos >= len) {
                reportEof("JASX1002", "unclosed fragment <>", opening.start(), opening.end());
                return new JasxFragment(opening.start(), len, opening, children, null);
            }
            if (atClosingTag()) {
                int start = pos;
                ElementName stray = parseClosingTagName();
                if (stray != null) {
                    mismatch(start, pos, stray, null, opening.start());
                }
                return new JasxFragment(opening.start(), pos, opening, children, new JasxClosingFragment(start, pos));
            }
            parseChild(children);
        }
    }

    private void parseChild(List<Child> children) {
        char c = src.charAt(pos);
        if (c == '{') {
            children.add(parseExpressionContainer(pos));
        } else if (c == '<') {
            Markup markup = parseMarkup(pos);
            pos = markup.end();
            children.add(markup);
        } else {
            children.add(parseText());
        }
    }

    private boolean atClosingTag() {
        if (src.charAt(pos) != '<') {
            return false;
        }
        int p = pos + 1;
        while (p < len && Character.isWhitespace(src.charAt(p))) {
            p++;
        }
        return p < len && src.charAt(p) == '/';
    }

    private JasxClosingElement parseClosingElement(JasxOpeningElement opening) {
        int start = pos;
        ElementName name = parseClosingTagName();
        if (name == null || !sameName(opening.name(), name)) {
            mismatch(start, pos, name, opening.name(), opening.start());
        }
        return new JasxClosingElement(start, pos, name != null ? name : new JasxIdentifier(start, start, ""));
    }

    /** Parses {@code </name>} or {@code </>} at {@code pos}; returns the name, or null for {@code </>}. */
    private ElementName parseClosingTagName() {
        pos++; // '<'
        skipWhitespace();
        pos++; // '/'
        skipWhitespace();
        ElementName name = parseElementName();
        skipWhitespace();
        if (pos < len && src.charAt(pos) == '>') {
            pos++;
        } else if (pos >= len) {
            reportEof("JASX1002", "unclosed closing tag", pos, len);
        } else {
            unexpected(false);
        }
        return name;
    }

    private void mismatch(int start, int end, ElementName closing, ElementName opening, int openedAt) {
        String open = opening == null ? "<>" : "<" + nameText(opening) + ">";
        String close = closing == null ? "</>" : "</" + nameText(closing) + ">";
        LineMap.Position p = lines().position(openedAt);
        int relStart = opening == null ? openedAt : opening.start();
        int relEnd = opening == null ? openedAt + 2 : opening.end();
        diagnostics.add(new Diagnostic("JASX1001", Diagnostic.Severity.ERROR,
                "closing tag " + close + " does not match " + open + " opened at " + p.line() + ":" + (p.column() + 1),
                start, end, List.of(new Diagnostic.Related(open + " opened here", relStart, relEnd))));
    }

    private ElementName parseElementName() {
        JasxIdentifier first = parseIdentifier();
        if (first == null) {
            return null;
        }
        ElementName name = first;
        while (pos < len && src.charAt(pos) == '.') {
            pos++;
            JasxIdentifier part = parseIdentifier();
            if (part == null) {
                unexpected(false);
                break;
            }
            name = new JasxQualifiedName(name.start(), part.end(), name, part);
        }
        if (name instanceof JasxQualifiedName) {
            checkNoDash(name);
        }
        return name;
    }

    /** The identifiers of a qualified name must not contain '-' (JASX1011). */
    private void checkNoDash(ElementName name) {
        if (name instanceof JasxQualifiedName q) {
            checkNoDash(q.qualifier());
            checkNoDash(q.name());
        } else if (name instanceof JasxIdentifier id && id.name().contains("-") && !id.name().endsWith("-")) {
            error("JASX1011", "a qualified name must not contain '-'", id.start(), id.end());
        }
    }

    private JasxIdentifier parseIdentifier() {
        if (!isIdentifierStart(pos)) {
            return null;
        }
        int start = pos;
        pos += Character.charCount(src.codePointAt(pos));
        while (pos < len) {
            int cp = src.codePointAt(pos);
            if (cp != '-' && !Character.isJavaIdentifierPart(cp)) {
                break;
            }
            pos += Character.charCount(cp);
        }
        String name = src.substring(start, pos);
        if (name.endsWith("-")) {
            error("JASX1011", "identifier '" + name + "' must not end with '-'", start, pos);
        }
        return new JasxIdentifier(start, pos, name);
    }

    private boolean isIdentifierStart(int p) {
        return p < len && Character.isJavaIdentifierStart(src.codePointAt(p));
    }

    private JasxAttribute parseAttribute() {
        int start = pos;
        JasxIdentifier first = parseIdentifier();
        AttributeName name = first;
        if (pos < len && src.charAt(pos) == ':') {
            pos++;
            JasxIdentifier local = parseIdentifier();
            if (local == null) {
                unexpected(false);
            } else {
                name = new JasxNamespacedName(start, local.end(), first, local);
            }
        }
        int afterName = pos;
        skipWhitespace();
        if (pos >= len || src.charAt(pos) != '=') {
            pos = afterName;
            return new JasxAttribute(start, afterName, name, null);
        }
        pos++;
        skipWhitespace();
        AttributeValue value = parseAttributeValue();
        return new JasxAttribute(start, value != null ? value.end() : pos, name, value);
    }

    private AttributeValue parseAttributeValue() {
        int start = pos;
        char c = charAt(pos);
        if (c == '"') {
            return parseStringLiteral();
        }
        if (c == '{') {
            JasxExpressionContainer container = parseExpressionContainer(start);
            if (container.expression() instanceof JasxEmptyExpression) {
                error("JASX1008", "an attribute value must not be an empty expression",
                        container.start(), container.end());
            }
            return container;
        }
        if (c == '<') {
            Markup markup = parseMarkup(start);
            pos = markup.end();
            return markup;
        }
        while (pos < len && !Character.isWhitespace(src.charAt(pos)) && src.charAt(pos) != '>'
                && !(src.charAt(pos) == '/' && charAt(pos + 1) == '>')) {
            pos++;
        }
        int end = pos > start ? pos : Math.min(start + 1, len);
        error("JASX1005", "an attribute value must be a string literal, an expression container or markup", start, end);
        return null;
    }

    private JasxStringLiteral parseStringLiteral() {
        int start = pos;
        pos++;
        StringBuilder value = new StringBuilder();
        while (true) {
            if (pos >= len || src.charAt(pos) == '\n' || src.charAt(pos) == '\r') {
                error("JASX1005", "unterminated string literal", start, pos);
                return new JasxStringLiteral(start, pos, value.toString(), src.substring(start, pos));
            }
            char c = src.charAt(pos);
            if (c == '"') {
                pos++;
                return new JasxStringLiteral(start, pos, value.toString(), src.substring(start, pos));
            }
            if (c == '\\') {
                parseEscape(value);
            } else {
                value.append(c);
                pos++;
            }
        }
    }

    /** A Java escape sequence (JLS §3.10.7). Unicode escapes are not processed in markup (grammar.md §2.1). */
    private void parseEscape(StringBuilder out) {
        int start = pos;
        pos++;
        char e = charAt(pos);
        switch (e) {
            case 'b' -> out.append('\b');
            case 't' -> out.append('\t');
            case 'n' -> out.append('\n');
            case 'f' -> out.append('\f');
            case 'r' -> out.append('\r');
            case 's' -> out.append(' ');
            case '"', '\'', '\\' -> out.append(e);
            case 'u' -> out.append('\\').append('u');
            default -> {
                if (e >= '0' && e <= '7') {
                    int max = e <= '3' ? 3 : 2;
                    int v = 0;
                    for (int n = 0; n < max && pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '7'; n++) {
                        v = v * 8 + (src.charAt(pos) - '0');
                        pos++;
                    }
                    out.append((char) v);
                    return;
                }
                error("JASX1005", "invalid escape sequence", start, Math.min(pos + 1, len));
                if (pos >= len || e == '\n' || e == '\r') {
                    return;
                }
                out.append(e);
            }
        }
        pos++;
    }

    /** A '{' in attribute position: a reserved spread attribute (JASX1007) or an error (JASX1006). */
    private void parseBracedAttribute() {
        int start = pos;
        int p = start + 1;
        while (p < len && Character.isWhitespace(src.charAt(p))) {
            p++;
        }
        boolean spread = src.startsWith("...", p);
        JasxExpressionContainer container = parseExpressionContainer(start);
        if (spread) {
            error("JASX1007", "spread attributes are reserved for a future version", container.start(), container.end());
        } else {
            error("JASX1006", "unexpected expression container in attribute position", container.start(), container.end());
        }
    }

    private JasxExpressionContainer parseExpressionContainer(int lbrace) {
        JavaScan scan = scanJava(lbrace + 1, true);
        int end;
        if (scan.closed()) {
            // The closing '}' is a Java-mode character, so it may be written as a Unicode escape.
            end = lexer.next(scan.end());
        } else {
            reportEof("JASX1003", "unclosed expression container", lbrace, lbrace + 1);
            end = len;
        }
        Expression expression = scan.empty()
                ? new JasxEmptyExpression(lbrace + 1, scan.end(), src.substring(lbrace + 1, scan.end()))
                : new JavaExpression(lbrace + 1, scan.end(), scan.segments());
        pos = end;
        return new JasxExpressionContainer(lbrace, end, expression);
    }

    private JasxText parseText() {
        int start = pos;
        StringBuilder value = new StringBuilder();
        while (pos < len) {
            char c = src.charAt(pos);
            if (c == '{' || c == '<') {
                break;
            }
            if (c == '}' || c == '>') {
                error("JASX1009", "'" + c + "' is not allowed in text; write " + (c == '}' ? "&#125;" : "&gt;"),
                        pos, pos + 1);
                value.append(c);
                pos++;
            } else if (c == '&') {
                parseCharacterReference(value);
            } else {
                value.append(c);
                pos++;
            }
        }
        return new JasxText(start, pos, value.toString(), src.substring(start, pos));
    }

    /** Decodes a character reference at {@code pos}; a '&' that starts none is literal text. */
    private void parseCharacterReference(StringBuilder out) {
        int start = pos;
        int p = pos + 1;
        if (charAt(p) == '#') {
            p++;
            boolean hex = charAt(p) == 'x' || charAt(p) == 'X';
            if (hex) {
                p++;
            }
            int digits = p;
            long cp = 0;
            while (p < len && (hex ? isAsciiHexDigit(src.charAt(p)) : isAsciiDigit(src.charAt(p)))) {
                cp = Math.min(cp * (hex ? 16 : 10) + Character.digit(src.charAt(p), 16), 0x110000);
                p++;
            }
            if (p > digits && charAt(p) == ';') {
                boolean valid = cp > 0 && cp <= Character.MAX_CODE_POINT && !(cp >= 0xD800 && cp <= 0xDFFF);
                out.appendCodePoint(valid ? (int) cp : 0xFFFD);
                pos = p + 1;
                return;
            }
        } else if (isAsciiLetter(charAt(p))) {
            int name = p;
            while (p < len && (isAsciiLetter(src.charAt(p)) || isAsciiDigit(src.charAt(p)))) {
                p++;
            }
            if (charAt(p) == ';') {
                String decoded = Entities.decode(src.substring(name, p));
                if (decoded == null) {
                    error("JASX1010", "unknown character reference '" + src.substring(start, p + 1) + "'", start, p + 1);
                    out.append(src, start, p + 1);
                } else {
                    out.append(decoded);
                }
                pos = p + 1;
                return;
            }
        }
        out.append('&');
        pos = start + 1;
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private char charAt(int p) {
        return p < len ? src.charAt(p) : '\0';
    }

    private void skipWhitespace() {
        while (pos < len && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    /** Reports JASX1006 at the character at {@code pos}, and skips it if {@code advance} is set. */
    private void unexpected(boolean advance) {
        if (pos >= len) {
            reportEof("JASX1006", "unexpected end of input", len, len);
            return;
        }
        int cp = src.codePointAt(pos);
        int end = pos + Character.charCount(cp);
        error("JASX1006", "unexpected '" + new String(Character.toChars(cp)) + "' in markup", pos, end);
        if (advance) {
            pos = end;
        }
    }

    private void error(String code, String message, int start, int end) {
        diagnostics.add(Diagnostic.error(code, message, start, end));
    }

    private void reportEof(String code, String message, int start, int end) {
        if (!eofReported) {
            eofReported = true;
            error(code, message, start, end);
        }
    }

    private LineMap lines() {
        if (lines == null) {
            lines = new LineMap(src);
        }
        return lines;
    }

    private static boolean sameName(ElementName a, ElementName b) {
        if (a instanceof JasxIdentifier x && b instanceof JasxIdentifier y) {
            return x.name().equals(y.name());
        }
        if (a instanceof JasxQualifiedName x && b instanceof JasxQualifiedName y) {
            return x.name().name().equals(y.name().name()) && sameName(x.qualifier(), y.qualifier());
        }
        return false;
    }

    /** The source text of an element name: {@code div}, {@code Ui.Card}. */
    public static String nameText(ElementName name) {
        return switch (name) {
            case JasxIdentifier id -> id.name();
            case JasxQualifiedName q -> nameText(q.qualifier()) + "." + q.name().name();
        };
    }

    /** The source text of an attribute name: {@code class}, {@code xlink:href}. */
    public static String attributeNameText(AttributeName name) {
        return switch (name) {
            case JasxIdentifier id -> id.name();
            case JasxNamespacedName ns -> ns.namespace().name() + ":" + ns.name().name();
        };
    }

    private static boolean isAsciiDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiHexDigit(int c) {
        return isAsciiDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isAsciiLetter(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
