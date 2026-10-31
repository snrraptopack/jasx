package jasx.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import jasx.parser.JavaLexer;
import jasx.parser.JavaLexer.Kind;
import jasx.parser.JavaLexer.Token;

/**
 * The declarations of a source file that the compiler needs, found by <em>tokenizing</em> only
 * (semantics.md §5.3). JASX never builds a Java syntax tree; javac checks the Java later.
 *
 * <p>What is collected:
 *
 * <ul>
 *   <li>the package name and the imports (for name resolution, semantics.md §5.4);
 *   <li>every named class, interface, enum, record and annotation type, with the range of its body
 *       (to know which class a piece of markup is in);
 *   <li>the header of every method declared directly in those types: annotations, modifiers, return
 *       type, name and parameters (to find components).
 * </ul>
 *
 * <p>How the scan works. The file is turned into a list of tokens, with each markup expression replaced
 * by one opaque token. Then one pass walks the tokens and keeps a stack of open braces:
 *
 * <ul>
 *   <li>A brace that opens a type body pushes that type. Inside a type body, tokens are collected into a
 *       "member" until a {@code ;} (a field or abstract method) or a {@code {} (a method body, an
 *       initializer, ...). When a {@code {} is reached, the member's tokens are checked: if they look like
 *       a method header, the method is recorded.
 *   <li>Any other brace (method bodies, blocks, lambdas, anonymous classes) pushes a "block". Everything
 *       inside a block is skipped until its matching {@code }}, because method bodies don't matter here.
 * </ul>
 *
 * <p>Parentheses are counted too, so that braces inside an annotation argument ({@code @A({1, 2})}) or a
 * record header don't count as bodies.
 */
final class JavaStructure {
    record Import(boolean isStatic, String name, boolean onDemand) {}

    /** An annotation such as {@code @jasx.Default("5")}: its name as written and its argument tokens. */
    record Annotation(String name, List<Token> arguments, int start, int end) {}

    /** A method parameter. {@code type} is the type as written, such as {@code List<String>}. */
    record Param(List<Annotation> annotations, String type, String name, int start, int end) {}

    /** A method header. {@code headerStart} is the offset of its first annotation or modifier. */
    record Method(TypeDecl owner, List<Annotation> annotations, Set<String> modifiers, String returnType,
            String name, int nameStart, int nameEnd, List<Param> params, int headerStart) {}

    /** A named type, with the offsets of its body braces. Nested types point to their enclosing type. */
    static final class TypeDecl {
        final String name;
        final TypeDecl parent;
        /** The source-style name used by the component index: {@code app.Page.Inner}. */
        final String qualifiedName;
        final int bodyStart;
        /** Set when the closing brace is found; the end of the file if it never is. */
        int bodyEnd;

        TypeDecl(String name, TypeDecl parent, String qualifiedName, int bodyStart, int bodyEnd) {
            this.name = name;
            this.parent = parent;
            this.qualifiedName = qualifiedName;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
        }
    }

    /** The text of the opaque token that stands for a markup expression. */
    private static final String MARKUP = "<jasx>";

    /** The stack entry for a brace that is not a type body. */
    private static final Object BLOCK = new Object();

    private static final Set<String> MODIFIERS = Set.of(
            "public", "protected", "private", "static", "final", "abstract", "synchronized", "native",
            "strictfp", "default", "transient", "volatile", "sealed");

    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "interface", "enum", "record");

    private final List<Token> tokens;
    private final int fileLength;

    String packageName = "";
    final List<Import> imports = new ArrayList<>();
    final List<TypeDecl> types = new ArrayList<>();
    final List<Method> methods = new ArrayList<>();

    private JavaStructure(List<Token> tokens, int fileLength) {
        this.tokens = tokens;
        this.fileLength = fileLength;
    }

    /**
     * Scans {@code source}.
     *
     * @param markupRanges the {@code [start, end)} range of each top-level markup expression, in order;
     *     each becomes one opaque token, so that braces and {@code <} inside markup are never seen as Java
     */
    static JavaStructure scan(String source, List<int[]> markupRanges) {
        JavaStructure structure = new JavaStructure(tokenize(source, markupRanges), source.length());
        structure.walk();
        return structure;
    }

    private static List<Token> tokenize(String source, List<int[]> markupRanges) {
        JavaLexer lexer = new JavaLexer(source);
        List<Token> tokens = new ArrayList<>();
        int nextMarkup = 0;

        int p = lexer.skipTrivia(0);
        while (p < source.length()) {
            // Skip past markup ranges that are already behind us.
            while (nextMarkup < markupRanges.size() && markupRanges.get(nextMarkup)[1] <= p) {
                nextMarkup++;
            }

            boolean atMarkup = nextMarkup < markupRanges.size() && markupRanges.get(nextMarkup)[0] == p;
            if (atMarkup) {
                int end = markupRanges.get(nextMarkup)[1];
                tokens.add(new Token(Kind.OPERATOR, MARKUP, p, end));
                p = lexer.skipTrivia(end);
            } else {
                Token token = lexer.lex(p);
                tokens.add(token);
                p = lexer.skipTrivia(token.end());
            }
        }
        return tokens;
    }

    /** The innermost named type whose body contains {@code offset}, or null if there is none. */
    TypeDecl enclosingType(int offset) {
        TypeDecl innermost = null;
        for (TypeDecl type : types) {
            boolean contains = type.bodyStart <= offset && offset < type.bodyEnd;
            // Nested bodies start later than the bodies that contain them.
            if (contains && (innermost == null || type.bodyStart > innermost.bodyStart)) {
                innermost = type;
            }
        }
        return innermost;
    }

    // ---------------------------------------------------------------------------------------------
    // The walk over the tokens
    // ---------------------------------------------------------------------------------------------

    /** The single pass over the tokens described in the class comment. */
    private void walk() {
        // Each entry is a TypeDecl (a type body) or BLOCK (any other brace).
        Deque<Object> braces = new ArrayDeque<>();
        int memberStart = 0; // index of the first token of the current member
        int parenDepth = 0;

        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);

            // Inside a block: only track braces until the block closes.
            if (braces.peek() == BLOCK) {
                if (t.is("{")) {
                    braces.push(BLOCK);
                } else if (t.is("}")) {
                    braces.pop();
                    boolean backInTypeBody = braces.peek() != BLOCK;
                    if (backInTypeBody) {
                        memberStart = i + 1;
                        parenDepth = 0;
                    }
                }
                continue;
            }

            // At the top level of the file (owner == null) or directly in a type body.
            TypeDecl owner = (TypeDecl) braces.peek();

            if (t.is("(")) {
                parenDepth++;
                continue;
            }
            if (t.is(")")) {
                parenDepth = Math.max(0, parenDepth - 1);
                continue;
            }
            if (parenDepth > 0) {
                continue; // inside an annotation argument or a parameter list
            }

            if (owner == null && t.is("package")) {
                i = readPackage(i);
                memberStart = i + 1;
            } else if (owner == null && t.is("import")) {
                i = readImport(i);
                memberStart = i + 1;
            } else if (isTypeDeclaration(i)) {
                int bodyBrace = findTypeBodyBrace(i + 2);
                if (bodyBrace < 0) {
                    continue;
                }
                TypeDecl type = declareType(tokens.get(i + 1).text(), owner, bodyBrace);
                braces.push(type);
                i = bodyBrace;
                memberStart = i + 1;
            } else if (t.is("{")) {
                // A body starts: the member before it may be a method header.
                recordMethodIfHeader(memberStart, i, owner);
                braces.push(BLOCK);
            } else if (t.is(";")) {
                memberStart = i + 1;
            } else if (t.is("}")) {
                // The end of a type body.
                if (owner != null) {
                    owner.bodyEnd = t.end();
                    braces.pop();
                }
                memberStart = i + 1;
            }
        }
    }

    /** {@code package a.b;} at index {@code i}; returns the index of the {@code ;}. */
    private int readPackage(int i) {
        int semicolon = indexOf(";", i + 1);
        packageName = join(i + 1, semicolon);
        return semicolon;
    }

    /** {@code import [static] a.b.C[.*];} at index {@code i}; returns the index of the {@code ;}. */
    private int readImport(int i) {
        int j = i + 1;
        boolean isStatic = j < tokens.size() && tokens.get(j).is("static");
        if (isStatic) {
            j++;
        }
        int semicolon = indexOf(";", j);
        String name = join(j, semicolon);
        boolean onDemand = name.endsWith(".*");
        if (onDemand) {
            name = name.substring(0, name.length() - 2);
        }
        imports.add(new Import(isStatic, name, onDemand));
        return semicolon;
    }

    /** Whether the token at {@code i} starts a type declaration: {@code class Name}, {@code record Name}, ... */
    private boolean isTypeDeclaration(int i) {
        Token t = tokens.get(i);
        boolean isKeyword = t.kind() == Kind.IDENTIFIER && TYPE_KEYWORDS.contains(t.text());
        boolean afterDot = i > 0 && tokens.get(i - 1).is("."); // Foo.class is not a declaration
        boolean nameFollows = i + 1 < tokens.size() && tokens.get(i + 1).kind() == Kind.IDENTIFIER;
        return isKeyword && !afterDot && nameFollows;
    }

    private TypeDecl declareType(String name, TypeDecl owner, int bodyBrace) {
        String qualifiedName;
        if (owner != null) {
            qualifiedName = owner.qualifiedName + "." + name;
        } else if (packageName.isEmpty()) {
            qualifiedName = name;
        } else {
            qualifiedName = packageName + "." + name;
        }
        TypeDecl type = new TypeDecl(name, owner, qualifiedName, tokens.get(bodyBrace).start(), fileLength);
        types.add(type);
        return type;
    }

    /**
     * The index of the {@code {} that opens a type body, skipping type parameters ({@code <T>}), a record
     * header ({@code (int x)}) and {@code extends}/{@code implements} clauses. Returns -1 if there is none.
     */
    private int findTypeBodyBrace(int from) {
        int depth = 0; // angle brackets and parentheses
        for (int j = from; j < tokens.size(); j++) {
            Token t = tokens.get(j);
            depth += angleBracketDelta(t);
            if (t.is("(")) {
                depth++;
            } else if (t.is(")")) {
                depth--;
            } else if (depth <= 0 && t.is("{")) {
                return j;
            } else if (depth <= 0 && t.is(";")) {
                return -1;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------
    // Method headers
    // ---------------------------------------------------------------------------------------------

    /**
     * Records a method if the tokens {@code [from, to)} that precede a {@code {} are a method header:
     *
     * <pre>
     *   [annotations] [modifiers] [&lt;type parameters&gt;] ReturnType name ( parameters ) [throws ...]
     * </pre>
     *
     * Constructors (no return type), fields with initializers ({@code = ...}), initializer blocks and
     * enum constants don't match and are ignored.
     */
    private void recordMethodIfHeader(int from, int to, TypeDecl owner) {
        if (owner == null || from >= to) {
            return;
        }

        // Leading annotations and modifiers.
        List<Annotation> annotations = new ArrayList<>();
        Set<String> modifiers = new LinkedHashSet<>();
        int k = from;
        while (k < to) {
            Token t = tokens.get(k);
            if (isAnnotationStart(k, to)) {
                k = readAnnotation(k, to, annotations);
            } else if (t.kind() == Kind.IDENTIFIER && MODIFIERS.contains(t.text())) {
                modifiers.add(t.text());
                k++;
            } else if (isNonSealed(k, to)) {
                k += 3; // "non", "-", "sealed"
            } else {
                break;
            }
        }

        // Type parameters of a generic method: <T>
        if (k < to && tokens.get(k).is("<")) {
            k = skipAngleBrackets(k, to);
        }

        // The parameter list's '(' is the first '(' outside angle brackets. A '=' before it means this
        // is a field initializer, not a method.
        int openParen = -1;
        int angleDepth = 0;
        for (int j = k; j < to; j++) {
            Token t = tokens.get(j);
            if (t.is("=")) {
                return;
            }
            angleDepth += angleBracketDelta(t);
            if (angleDepth <= 0 && t.is("(")) {
                openParen = j;
                break;
            }
        }
        if (openParen < 0) {
            return;
        }

        // The name is the identifier before '('; the return type is everything between the modifiers and
        // the name. No return type means a constructor.
        int nameIndex = openParen - 1;
        boolean hasName = nameIndex >= k && tokens.get(nameIndex).kind() == Kind.IDENTIFIER;
        boolean hasReturnType = nameIndex > k;
        if (!hasName || !hasReturnType) {
            return;
        }

        int closeParen = matchingParen(openParen, to);
        if (closeParen < 0) {
            return;
        }

        Token name = tokens.get(nameIndex);
        methods.add(new Method(
                owner,
                List.copyOf(annotations),
                modifiers,
                typeText(k, nameIndex),
                name.text(),
                name.start(),
                name.end(),
                readParams(openParen + 1, closeParen),
                tokens.get(from).start()));
    }

    /** Splits the tokens of a parameter list at the commas that are not nested in brackets. */
    private List<Param> readParams(int from, int to) {
        List<Param> params = new ArrayList<>();
        int depth = 0; // (), [], {} and <>, so that Map<K, V> isn't split at its comma
        int paramStart = from;

        for (int j = from; j <= to; j++) {
            boolean atEnd = j == to;
            boolean atSeparator = !atEnd && depth == 0 && tokens.get(j).is(",");
            if (atEnd || atSeparator) {
                Param param = readParam(paramStart, j);
                if (param != null) {
                    params.add(param);
                }
                paramStart = j + 1;
                continue;
            }

            Token t = tokens.get(j);
            depth += angleBracketDelta(t);
            if (t.is("(") || t.is("[") || t.is("{")) {
                depth++;
            } else if (t.is(")") || t.is("]") || t.is("}")) {
                depth--;
            }
        }
        return params;
    }

    /**
     * One parameter: {@code [annotations] [final] Type name}, also accepting the old array form
     * {@code Type name[]}.
     */
    private Param readParam(int from, int to) {
        if (from >= to) {
            return null;
        }

        List<Annotation> annotations = new ArrayList<>();
        int j = from;
        while (j < to) {
            if (isAnnotationStart(j, to)) {
                j = readAnnotation(j, to, annotations);
            } else if (tokens.get(j).is("final")) {
                j++;
            } else {
                break;
            }
        }

        // Array brackets after the name belong to the type.
        int end = to;
        StringBuilder arrayDims = new StringBuilder();
        while (end - 2 >= j && tokens.get(end - 1).is("]") && tokens.get(end - 2).is("[")) {
            arrayDims.append("[]");
            end -= 2;
        }

        // The name is the last identifier; the type is everything before it.
        int nameIndex = end - 1;
        boolean hasType = nameIndex > j;
        if (!hasType || tokens.get(nameIndex).kind() != Kind.IDENTIFIER) {
            return null;
        }
        String type = typeText(j, nameIndex) + arrayDims;
        return new Param(List.copyOf(annotations), type, tokens.get(nameIndex).text(),
                tokens.get(from).start(), tokens.get(to - 1).end());
    }

    private boolean isAnnotationStart(int k, int limit) {
        boolean isAt = tokens.get(k).is("@");
        boolean nameFollows = k + 1 < limit && tokens.get(k + 1).kind() == Kind.IDENTIFIER;
        boolean isAnnotationTypeDeclaration = nameFollows && tokens.get(k + 1).is("interface");
        return isAt && nameFollows && !isAnnotationTypeDeclaration;
    }

    private boolean isNonSealed(int k, int limit) {
        return tokens.get(k).is("non") && k + 2 < limit && tokens.get(k + 1).is("-") && tokens.get(k + 2).is("sealed");
    }

    /**
     * Reads the annotation at {@code k}: {@code @Name}, {@code @a.b.Name} or {@code @Name(arguments)}.
     * Adds it to {@code out} and returns the index after it.
     */
    private int readAnnotation(int k, int limit, List<Annotation> out) {
        int start = tokens.get(k).start();

        // The (possibly qualified) name.
        int j = k + 1;
        StringBuilder name = new StringBuilder(tokens.get(j).text());
        j++;
        while (j + 1 < limit && tokens.get(j).is(".") && tokens.get(j + 1).kind() == Kind.IDENTIFIER) {
            name.append('.').append(tokens.get(j + 1).text());
            j += 2;
        }

        // The optional argument list.
        List<Token> arguments = List.of();
        int end = tokens.get(j - 1).end();
        if (j < limit && tokens.get(j).is("(")) {
            int closeParen = matchingParen(j, limit);
            if (closeParen < 0) {
                closeParen = limit - 1;
            }
            arguments = List.copyOf(tokens.subList(j + 1, closeParen));
            end = tokens.get(closeParen).end();
            j = closeParen + 1;
        }

        out.add(new Annotation(name.toString(), arguments, start, end));
        return j;
    }

    // ---------------------------------------------------------------------------------------------
    // Token helpers
    // ---------------------------------------------------------------------------------------------

    /** Returns the index after the angle brackets that open at {@code k}: {@code <T, U extends List<T>>}. */
    private int skipAngleBrackets(int k, int limit) {
        int depth = 0;
        for (int j = k; j < limit; j++) {
            depth += angleBracketDelta(tokens.get(j));
            if (depth <= 0) {
                return j + 1;
            }
        }
        return limit;
    }

    /** The index of the {@code )} that matches the {@code (} at {@code open}, or -1. */
    private int matchingParen(int open, int limit) {
        int depth = 0;
        for (int j = open; j < limit; j++) {
            if (tokens.get(j).is("(")) {
                depth++;
            } else if (tokens.get(j).is(")")) {
                depth--;
                if (depth == 0) {
                    return j;
                }
            }
        }
        return -1;
    }

    /**
     * How a token changes the angle-bracket depth. The lexer reads {@code >>} and {@code >>>} as single
     * operators, but in {@code List<List<T>>} they close two or three brackets.
     */
    private static int angleBracketDelta(Token t) {
        if (t.kind() != Kind.OPERATOR) {
            return 0;
        }
        return switch (t.text()) {
            case "<" -> 1;
            case ">" -> -1;
            case ">>" -> -2;
            case ">>>" -> -3;
            default -> 0;
        };
    }

    /** The index of the next token {@code text} from {@code from}, or the last index if there is none. */
    private int indexOf(String text, int from) {
        for (int j = from; j < tokens.size(); j++) {
            if (tokens.get(j).is(text)) {
                return j;
            }
        }
        return tokens.size() - 1;
    }

    /** The texts of the tokens {@code [from, to)}, concatenated: {@code a.b.C}. */
    private String join(int from, int to) {
        StringBuilder text = new StringBuilder();
        for (int j = from; j < to && j < tokens.size(); j++) {
            text.append(tokens.get(j).text());
        }
        return text.toString();
    }

    /**
     * The text of a type written with tokens {@code [from, to)}, with a space only between adjacent words:
     * {@code List<String>}, {@code Map<String, ? extends T>} (written as {@code Map<String,? extends T>}).
     */
    private String typeText(int from, int to) {
        StringBuilder text = new StringBuilder();
        Token previous = null;
        for (int j = from; j < to; j++) {
            Token t = tokens.get(j);
            if (previous != null && isWord(previous) && isWord(t)) {
                text.append(' ');
            }
            text.append(t.text());
            previous = t;
        }
        return text.toString();
    }

    private static boolean isWord(Token t) {
        return t.kind() == Kind.IDENTIFIER || t.is("?");
    }
}
