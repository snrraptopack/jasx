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
import jasx.parser.ast.Ast.JasxMarkupStatement;
import jasx.parser.ast.Ast.JasxNamespacedName;
import jasx.parser.ast.Ast.JasxOpeningElement;
import jasx.parser.ast.Ast.JasxOpeningFragment;
import jasx.parser.ast.Ast.JasxQualifiedName;
import jasx.parser.ast.Ast.JasxStatementContainer;
import jasx.parser.ast.Ast.JasxStringLiteral;
import jasx.parser.ast.Ast.JasxStyleBlock;
import jasx.parser.ast.Ast.JasxText;
import jasx.parser.ast.Ast.JavaCode;
import jasx.parser.ast.Ast.JavaExpression;
import jasx.parser.ast.Ast.JavaStatement;
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
        JavaScan scan = parser.scanJava(0, JavaContext.TOP_LEVEL);
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
    /** A complete style block, which ends a member like a {@code ;} does. */
    private static final Tok STYLE_BLOCK = Tok.of("<style>");
    /** A complete markup statement, which ends a statement like a {@code ;} does (grammar.md §6.7). */
    private static final Tok MARKUP_STATEMENT = Tok.of("<jasx>;");

    /**
     * Where Java mode is being scanned: the top level of the file, the content of an expression container,
     * or the content of a statement container, where markup can also start a statement (grammar.md §6.7).
     */
    private enum JavaContext { TOP_LEVEL, EXPRESSION, STATEMENT }

    /** The tokens after which a child {@code {} holds a statement rather than an expression (grammar.md §6.5). */
    private static final Set<String> STATEMENT_STARTERS = Set.of("{", "if", "for", "while", "do");

    private static final String STYLE_OPEN = "<style>";
    private static final String STYLE_CLOSE = "</style>";

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
     * Scans Java mode from {@code from}. At top level it runs to end of input; inside a container it stops
     * before the '}' that closes the container.
     */
    private JavaScan scanJava(int from, JavaContext context) {
        boolean inContainer = context != JavaContext.TOP_LEVEL;
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
            boolean markupStatement = context == JavaContext.STATEMENT && src.charAt(pos) == '<'
                    && atStatementStart(prev, brackets);
            if (context == JavaContext.TOP_LEVEL && startsStyleBlock(prev, brackets)) {
                addJavaCode(segments, chunk, pos);
                JasxStyleBlock block = parseStyleBlock(pos);
                segments.add(block);
                pos = block.end();
                chunk = pos;
                prev = STYLE_BLOCK;
            } else if (markupStatement) {
                addJavaCode(segments, chunk, pos);
                Segment statement = parseMarkupStatement(pos);
                segments.add(statement);
                pos = statement.end();
                chunk = pos;
                prev = statement instanceof JasxStyleBlock ? STYLE_BLOCK : MARKUP_STATEMENT;
            } else if (src.charAt(pos) == '<' && introducesMarkup(prev)) {
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

    /**
     * Whether a {@code <} preceded by {@code prev} is at the start of a statement in a statement container
     * (grammar.md §6.7): after {@code ;} (not in a {@code for} header), {@code }}, a block {@code {}, or
     * another markup statement or style block.
     */
    private static boolean atStatementStart(Tok prev, Deque<Bracket> brackets) {
        if (prev == MARKUP_STATEMENT || prev == STYLE_BLOCK) {
            return true;
        }
        return switch (prev.text()) {
            case ";" -> brackets.isEmpty() || brackets.peek().kind() != '(';
            case "}" -> true;
            case "{" -> !prev.arrayInit();
            default -> false;
        };
    }

    /**
     * Parses a markup statement whose {@code <} is at {@code lt}: the markup and the {@code ;} after it, if
     * any (grammar.md §6.7). A {@code <style>} whose content isn't an expression is a style block instead.
     */
    private Segment parseMarkupStatement(int lt) {
        if (startsStyleBlockChild()) {
            return parseStyleBlock(lt);
        }
        Markup markup = parseMarkup(lt);
        int end = markup.end();
        int next = lexer.skipTrivia(end);
        if (lexer.charAt(next) == ';') {
            end = lexer.next(next); // the ';' may be written as a Unicode escape
        }
        return new JasxMarkupStatement(lt, end, markup);
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
    // Style blocks (grammar.md §7.3)
    // ---------------------------------------------------------------------------------------------

    /**
     * Whether a style block starts at {@code pos}: the text is exactly {@code <style>}, and it is where a
     * statement or member begins (after {@code ;}, {@code }}, a block or body {@code {}, another style
     * block, or at the start of the file), directly inside braces or at the top level.
     */
    private boolean startsStyleBlock(Tok prev, Deque<Bracket> brackets) {
        if (!src.startsWith(STYLE_OPEN, pos)) {
            return false;
        }
        boolean atDeclarationStart = switch (prev.text()) {
            case "", ";", "}" -> true;
            case "{" -> !prev.arrayInit();
            default -> prev == STYLE_BLOCK;
        };
        boolean directlyInBraces = brackets.isEmpty() || brackets.peek().kind() == '{';
        return atDeclarationStart && directlyInBraces;
    }

    /**
     * Whether a style block starts at {@code pos} among the children of an element (grammar.md §6.5): the
     * text is exactly {@code <style>}, and its content doesn't start with {@code {}. So
     * {@code <style>{css}</style>} stays an ordinary element whose child is an expression.
     */
    private boolean startsStyleBlockChild() {
        if (!src.startsWith(STYLE_OPEN, pos)) {
            return false;
        }
        int contentStart = pos + STYLE_OPEN.length();
        int firstNonSpace = contentStart;
        while (firstNonSpace < len && Character.isWhitespace(src.charAt(firstNonSpace))) {
            firstNonSpace++;
        }
        boolean contentIsExpression = firstNonSpace < len && src.charAt(firstNonSpace) == '{';
        return !contentIsExpression;
    }

    /**
     * Parses the style block whose {@code <style>} starts at {@code lt}. Its content is raw text up to the
     * first {@code </style>}; nothing inside is tokenized. Without {@code </style>}, the block runs to the
     * end of the file (JASX1012).
     */
    private JasxStyleBlock parseStyleBlock(int lt) {
        int contentStart = lt + STYLE_OPEN.length();
        int close = src.indexOf(STYLE_CLOSE, contentStart);
        if (close < 0) {
            reportEof("JASX1012", "the style block is not closed: </style> is missing", lt, contentStart);
            return new JasxStyleBlock(lt, len, src.substring(contentStart));
        }
        return new JasxStyleBlock(lt, close + STYLE_CLOSE.length(), src.substring(contentStart, close));
    }

    // ---------------------------------------------------------------------------------------------
    // Markup mode (grammar.md §5, §6)
    // ---------------------------------------------------------------------------------------------

    /**
     * Parses an element or a fragment that starts at the {@code <} at {@code lt}, and leaves {@code pos} at
     * its end. {@code <>} starts a fragment; anything else must be an element name.
     */
    private Markup parseMarkup(int lt) {
        pos = lt + 1;
        skipWhitespace();
        boolean isFragment = pos < len && src.charAt(pos) == '>';
        if (isFragment) {
            pos++;
            return parseFragment(new JasxOpeningFragment(lt, pos));
        }
        return parseElement(lt);
    }

    /**
     * Parses an element: the opening tag with its attributes, then, unless it is self-closing
     * ({@code <br />}), its children and closing tag.
     */
    private JasxElement parseElement(int lt) {
        ElementName name = parseElementName();
        if (name == null) {
            // "<" followed by something that isn't a name. Report it and return an empty element, so that
            // parsing can continue right after the "<".
            if (pos >= len) {
                reportEof("JASX1002", "unclosed element", lt, len);
            } else {
                unexpected(false);
            }
            JasxIdentifier missing = new JasxIdentifier(pos, pos, "");
            JasxOpeningElement opening = new JasxOpeningElement(lt, pos, missing, List.of(), true);
            return new JasxElement(lt, pos, opening, List.of(), null);
        }

        // The attributes, up to "/>" or ">".
        List<JasxAttribute> attributes = new ArrayList<>();
        Set<String> attributeNames = new HashSet<>();
        while (true) {
            skipWhitespace();

            if (pos >= len) {
                reportEof("JASX1002", "unclosed element <" + nameText(name) + ">", lt, len);
                JasxOpeningElement opening = new JasxOpeningElement(lt, len, name, attributes, false);
                return new JasxElement(lt, len, opening, List.of(), null);
            }

            char c = src.charAt(pos);
            boolean selfClosing = c == '/' && charAt(pos + 1) == '>';
            if (selfClosing) {
                pos += 2;
                JasxOpeningElement opening = new JasxOpeningElement(lt, pos, name, attributes, true);
                return new JasxElement(lt, pos, opening, List.of(), null);
            }
            if (c == '>') {
                pos++;
                JasxOpeningElement opening = new JasxOpeningElement(lt, pos, name, attributes, false);
                return parseElementBody(opening);
            }

            if (c == '{') {
                parseBracedAttribute(); // {...spread} or a stray {expr}: always an error
            } else if (isIdentifierStart(pos)) {
                JasxAttribute attribute = parseAttribute();
                String attributeName = attributeNameText(attribute.name());
                boolean isDuplicate = !attributeNames.add(attributeName);
                if (isDuplicate) {
                    error("JASX1004", "duplicate attribute '" + attributeName + "'", attribute.start(), attribute.end());
                }
                attributes.add(attribute);
            } else {
                unexpected(true);
            }
        }
    }

    /** Parses the children of an element and its closing tag. */
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

    /** Parses the children of a fragment and its closing {@code </>}. */
    private JasxFragment parseFragment(JasxOpeningFragment opening) {
        List<Child> children = new ArrayList<>();
        while (true) {
            if (pos >= len) {
                reportEof("JASX1002", "unclosed fragment <>", opening.start(), opening.end());
                return new JasxFragment(opening.start(), len, opening, children, null);
            }
            if (atClosingTag()) {
                int start = pos;
                ElementName closingName = parseClosingTagName();
                if (closingName != null) {
                    // A fragment must close with </>, not </name>.
                    mismatch(start, pos, closingName, null, opening.start());
                }
                JasxClosingFragment closing = new JasxClosingFragment(start, pos);
                return new JasxFragment(opening.start(), pos, opening, children, closing);
            }
            parseChild(children);
        }
    }

    /** Parses one child at {@code pos}: a container, nested markup, a style block, or text. */
    private void parseChild(List<Child> children) {
        char c = src.charAt(pos);
        if (c == '{' && startsStatementContainer(pos)) {
            children.add(parseStatementContainer(pos));
        } else if (c == '{') {
            children.add(parseExpressionContainer(pos));
        } else if (startsStyleBlockChild()) {
            JasxStyleBlock block = parseStyleBlock(pos);
            pos = block.end();
            children.add(block);
        } else if (c == '<') {
            Markup markup = parseMarkup(pos);
            pos = markup.end();
            children.add(markup);
        } else {
            children.add(parseText());
        }
    }

    /** Whether a closing tag starts at {@code pos}: {@code <}, optional whitespace, then {@code /}. */
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

    /**
     * Parses the closing tag of {@code opening}. A different name is reported (JASX1001), but the tag
     * still closes the element, so that parsing can go on.
     */
    private JasxClosingElement parseClosingElement(JasxOpeningElement opening) {
        int start = pos;
        ElementName name = parseClosingTagName();
        boolean matches = name != null && sameName(opening.name(), name);
        if (!matches) {
            mismatch(start, pos, name, opening.name(), opening.start());
        }
        if (name == null) {
            name = new JasxIdentifier(start, start, ""); // "</>" closing an element
        }
        return new JasxClosingElement(start, pos, name);
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

    /**
     * Reports JASX1001: the closing tag at {@code [start, end)} doesn't match what was opened at
     * {@code openedAt}. A null {@code opening} means a fragment ({@code <>}); a null {@code closing} means
     * {@code </>}. The opening tag is attached as a related location.
     */
    private void mismatch(int start, int end, ElementName closing, ElementName opening, int openedAt) {
        String openText = opening == null ? "<>" : "<" + nameText(opening) + ">";
        String closeText = closing == null ? "</>" : "</" + nameText(closing) + ">";
        LineMap.Position opened = lines().position(openedAt);
        String message = "closing tag " + closeText + " does not match " + openText
                + " opened at " + opened.line() + ":" + (opened.column() + 1);

        // Point the related location at the opening name, or at "<>" for a fragment.
        int relatedStart = opening == null ? openedAt : opening.start();
        int relatedEnd = opening == null ? openedAt + 2 : opening.end();
        Diagnostic.Related openedHere = new Diagnostic.Related(openText + " opened here", relatedStart, relatedEnd);

        diagnostics.add(new Diagnostic("JASX1001", Diagnostic.Severity.ERROR, message, start, end, List.of(openedHere)));
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

    /**
     * Parses a Java escape sequence (JLS §3.10.7) at the backslash at {@code pos}, appending its value to
     * {@code out}.
     *
     * <p>Unicode escapes are not processed in markup (grammar.md §2.1), so a backslash followed by
     * {@code u} is kept as those two characters.
     */
    private void parseEscape(StringBuilder out) {
        int backslash = pos;
        pos++;
        char escape = charAt(pos);

        // Octal escapes have several digits, so they advance pos themselves.
        if (escape >= '0' && escape <= '7') {
            parseOctalEscape(out);
            return;
        }

        switch (escape) {
            case 'b' -> out.append('\b');
            case 't' -> out.append('\t');
            case 'n' -> out.append('\n');
            case 'f' -> out.append('\f');
            case 'r' -> out.append('\r');
            case 's' -> out.append(' ');
            case '"', '\'', '\\' -> out.append(escape);
            case 'u' -> out.append('\\').append('u');
            default -> {
                error("JASX1005", "invalid escape sequence", backslash, Math.min(pos + 1, len));
                boolean atLineEnd = pos >= len || escape == '\n' || escape == '\r';
                if (atLineEnd) {
                    return; // leave the line break for the unterminated-string check
                }
                out.append(escape); // keep the character, to continue after the error
            }
        }
        pos++;
    }

    /** An octal escape: up to three digits if the first is 0–3, otherwise up to two ({@code \0} to {@code \377}). */
    private void parseOctalEscape(StringBuilder out) {
        int maxDigits = charAt(pos) <= '3' ? 3 : 2;
        int code = 0;
        for (int digits = 0; digits < maxDigits; digits++) {
            char c = charAt(pos);
            if (c < '0' || c > '7') {
                break;
            }
            code = code * 8 + (c - '0');
            pos++;
        }
        out.append((char) code);
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

    /**
     * Parses an expression container {@code {...}} whose {@code {} is at {@code lbrace}. Its content is
     * scanned in Java mode, which may find nested markup. A container with only whitespace and comments
     * is a {@code JasxEmptyExpression}.
     */
    private JasxExpressionContainer parseExpressionContainer(int lbrace) {
        JavaScan scan = scanJava(lbrace + 1, JavaContext.EXPRESSION);

        int end;
        if (scan.closed()) {
            // The closing '}' is a Java-mode character, so it may be written as a Unicode escape:
            // step over the whole character, not just one char.
            end = lexer.next(scan.end());
        } else {
            reportEof("JASX1003", "unclosed expression container", lbrace, lbrace + 1);
            end = len;
        }

        int contentStart = lbrace + 1;
        int contentEnd = scan.end();
        Expression expression;
        if (scan.empty()) {
            expression = new JasxEmptyExpression(contentStart, contentEnd, src.substring(contentStart, contentEnd));
        } else {
            expression = new JavaExpression(contentStart, contentEnd, scan.segments());
        }

        pos = end;
        return new JasxExpressionContainer(lbrace, end, expression);
    }

    /**
     * Whether the child container whose {@code {} is at {@code lbrace} holds a statement: its first
     * significant token is {@code {}, {@code if}, {@code for}, {@code while} or {@code do} (grammar.md §6.5).
     */
    private boolean startsStatementContainer(int lbrace) {
        int first = lexer.skipTrivia(lbrace + 1);
        if (lexer.charAt(first) < 0) {
            return false;
        }
        JavaLexer.Token token = lexer.lex(first);
        boolean keywordOrBrace = token.kind() == JavaLexer.Kind.IDENTIFIER || token.kind() == JavaLexer.Kind.OPERATOR;
        return keywordOrBrace && STATEMENT_STARTERS.contains(token.text());
    }

    /**
     * Parses a statement container {@code {...}} whose {@code {} is at {@code lbrace} (grammar.md §6.7). Its
     * content is scanned in Java mode, where markup at the start of a statement is a markup statement.
     */
    private JasxStatementContainer parseStatementContainer(int lbrace) {
        JavaScan scan = scanJava(lbrace + 1, JavaContext.STATEMENT);

        int end;
        if (scan.closed()) {
            end = lexer.next(scan.end()); // the closing '}' may be a Unicode escape, as for expressions
        } else {
            reportEof("JASX1003", "unclosed statement container", lbrace, lbrace + 1);
            end = len;
        }

        JavaStatement statement = new JavaStatement(lbrace + 1, scan.end(), scan.segments());
        pos = end;
        return new JasxStatementContainer(lbrace, end, statement);
    }

    /**
     * Parses a run of text up to the next {@code {} or {@code <}. Character references are decoded in
     * {@code value}; {@code raw} keeps the source. A {@code }} or {@code >} in text is an error (JASX1009),
     * but is kept as text so that parsing continues.
     */
    private JasxText parseText() {
        int start = pos;
        StringBuilder value = new StringBuilder();
        while (pos < len) {
            char c = src.charAt(pos);
            if (c == '{' || c == '<') {
                break;
            }
            if (c == '}' || c == '>') {
                String replacement = c == '}' ? "&#125;" : "&gt;";
                error("JASX1009", "'" + c + "' is not allowed in text; write " + replacement, pos, pos + 1);
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

    /**
     * Decodes the character reference that starts with the {@code &} at {@code pos} (grammar.md §5):
     * {@code &#65;} (decimal), {@code &#x41;} (hexadecimal), or {@code &amp;} (named). A {@code &} that
     * doesn't start a complete reference, such as in {@code a & b}, is literal text.
     */
    private void parseCharacterReference(StringBuilder out) {
        int ampersand = pos;
        int afterAmpersand = pos + 1;

        boolean decoded;
        if (charAt(afterAmpersand) == '#') {
            decoded = parseNumericReference(ampersand, afterAmpersand + 1, out);
        } else if (isAsciiLetter(charAt(afterAmpersand))) {
            decoded = parseNamedReference(ampersand, afterAmpersand, out);
        } else {
            decoded = false;
        }

        if (!decoded) {
            out.append('&');
            pos = ampersand + 1;
        }
    }

    /**
     * {@code &#65;} or {@code &#x41;}, with the digits (or the {@code x}) starting at {@code p}. Returns
     * false, consuming nothing, if there are no digits or no {@code ;}.
     */
    private boolean parseNumericReference(int ampersand, int p, StringBuilder out) {
        boolean hex = charAt(p) == 'x' || charAt(p) == 'X';
        if (hex) {
            p++;
        }

        int digitsStart = p;
        long codePoint = 0;
        while (p < len && (hex ? isAsciiHexDigit(src.charAt(p)) : isAsciiDigit(src.charAt(p)))) {
            int digit = Character.digit(src.charAt(p), 16);
            // Cap the value just above the Unicode range, so that long digit runs can't overflow.
            codePoint = Math.min(codePoint * (hex ? 16 : 10) + digit, Character.MAX_CODE_POINT + 1);
            p++;
        }

        boolean complete = p > digitsStart && charAt(p) == ';';
        if (!complete) {
            return false;
        }

        // Like HTML, decode 0, surrogates and values past U+10FFFF as U+FFFD (the replacement character).
        boolean isSurrogate = codePoint >= 0xD800 && codePoint <= 0xDFFF;
        boolean valid = codePoint > 0 && codePoint <= Character.MAX_CODE_POINT && !isSurrogate;
        out.appendCodePoint(valid ? (int) codePoint : 0xFFFD);
        pos = p + 1;
        return true;
    }

    /**
     * {@code &name;} with the name starting at {@code p}. An unknown name is an error (JASX1010) and is
     * kept as written. Returns false, consuming nothing, if there is no {@code ;} after the name.
     */
    private boolean parseNamedReference(int ampersand, int p, StringBuilder out) {
        int nameStart = p;
        while (p < len && (isAsciiLetter(src.charAt(p)) || isAsciiDigit(src.charAt(p)))) {
            p++;
        }
        if (charAt(p) != ';') {
            return false;
        }

        int end = p + 1; // after the ';'
        String decoded = Entities.decode(src.substring(nameStart, p));
        if (decoded == null) {
            error("JASX1010", "unknown character reference '" + src.substring(ampersand, end) + "'", ampersand, end);
            out.append(src, ampersand, end);
        } else {
            out.append(decoded);
        }
        pos = end;
        return true;
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
