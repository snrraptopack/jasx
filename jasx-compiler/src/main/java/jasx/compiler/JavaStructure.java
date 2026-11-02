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
 * record header don't count as bodies. Braces inside a field initializer (an array initializer, a lambda
 * body, an anonymous class) are skipped like blocks, but they don't end the field.
 *
 * <p><b>File classes</b> (semantics.md §9). In a {@code .jasx} file, fields and methods may be declared at
 * the top level. The scan records every top-level declaration, and if there is a top-level field or
 * method, it creates the file's <em>file class</em>: a {@link TypeDecl} named after the file that contains
 * all top-level declarations. Types declared at the top level of such a file become nested in it.
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

    /**
     * A named type, with the offsets of its body braces. Nested types point to their enclosing type.
     *
     * <p>{@code parent} and {@code qualifiedName} are only final after the scan: a type declared at the top
     * level of a file is nested in the file class, which may only be discovered by a later declaration.
     */
    static final class TypeDecl {
        final String name;
        TypeDecl parent;
        /** The source-style name used by the component index: {@code app.Page.Inner}. */
        String qualifiedName;
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

    /** What a top-level declaration is. Only fields, methods and types are allowed (semantics.md §9.2). */
    enum TopLevelKind {
        FIELD,
        METHOD,
        TYPE,
        CONSTRUCTOR,
        INITIALIZER
    }

    /**
     * A declaration at the top level of a {@code .jasx} file.
     *
     * @param start the offset of its first token (an annotation, a modifier, or the declaration itself)
     * @param end the end of its header: the last token before its body or {@code ;}, or its keyword
     * @param staticAt where {@code static} goes: after the annotations and modifiers (semantics.md §9.4)
     * @param isStatic whether it is already declared {@code static}
     */
    record TopLevelDecl(TopLevelKind kind, int start, int end, int staticAt, boolean isStatic) {}

    /**
     * A part of the file that is not Java and becomes one opaque token: a markup expression, or a style
     * block, so that braces and {@code <} inside it are never seen as Java.
     */
    record Opaque(int start, int end, OpaqueKind kind) {}

    /** What an opaque range is: markup (an expression), a style block, or a script block (semantics.md §11.1). */
    enum OpaqueKind { MARKUP, STYLE, SCRIPT }

    /**
     * Where a style block is (semantics.md §10.1).
     *
     * @param owner the innermost class it is in (in its body, or in a method of it; the file class at the
     *     top level), or null if it is in no class: at the top level of a file without a file class
     */
    record StylePlace(int start, int end, TypeDecl owner) {}

    /** The text of the opaque token that stands for a markup expression. */
    private static final String MARKUP = "<jasx>";

    /** The text of the opaque token that stands for a style block. */
    private static final String STYLE_BLOCK = "<style>";

    /** The text of the opaque token that stands for a script block: like a style block, it ends a member. */
    private static final String SCRIPT_BLOCK = "<script>";

    /** The stack entry for a brace that is not a type body. */
    private static final Object BLOCK = new Object();

    /**
     * The stack entry for a brace inside a field initializer: {@code int[] xs = {1, 2};}, a lambda body, an
     * anonymous class. Skipped like a block, but the field continues after it.
     */
    private static final Object INITIALIZER_BRACE = new Object();

    private static final Set<String> MODIFIERS = Set.of(
            "public", "protected", "private", "static", "final", "abstract", "synchronized", "native",
            "strictfp", "default", "transient", "volatile", "sealed");

    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "interface", "enum", "record");

    private final List<Token> tokens;
    private final int fileLength;
    /** The name a file class would have, or null if the file can't have one (a {@code .java} file). */
    private final String fileClassName;

    String packageName = "";
    final List<Import> imports = new ArrayList<>();
    final List<TypeDecl> types = new ArrayList<>();
    final List<Method> methods = new ArrayList<>();

    /** The declarations at the top level of a {@code .jasx} file, in source order. */
    final List<TopLevelDecl> topLevel = new ArrayList<>();
    /** The file class, or null if the file has no top-level field or method (semantics.md §9.1). */
    TypeDecl fileClass;
    /** The end of the last package or import declaration, where the file class starts (semantics.md §9.4). */
    int afterImports;

    /** The style blocks of the file, in source order. */
    final List<StylePlace> styleBlocks = new ArrayList<>();

    /** A style block as the walk saw it, with the innermost type around it (null if none, or the file class). */
    private record StyleSighting(Token token, TypeDecl owner) {}

    private final List<StyleSighting> styleSightings = new ArrayList<>();

    private JavaStructure(List<Token> tokens, int fileLength, String fileClassName) {
        this.tokens = tokens;
        this.fileLength = fileLength;
        this.fileClassName = fileClassName;
    }

    /**
     * Scans {@code source}.
     *
     * @param opaque the top-level markup expressions and style blocks, in order; each becomes one token
     * @param fileClassName the name of the file class if the file turns out to need one (the file name
     *     without {@code .jasx}), or null for a {@code .java} file, whose top-level declarations are not
     *     recorded
     */
    static JavaStructure scan(String source, List<Opaque> opaque, String fileClassName) {
        JavaStructure structure = new JavaStructure(tokenize(source, opaque), source.length(), fileClassName);
        structure.walk();
        structure.nestTopLevelTypesInFileClass();
        structure.placeStyleBlocks();
        return structure;
    }

    private static List<Token> tokenize(String source, List<Opaque> opaque) {
        JavaLexer lexer = new JavaLexer(source);
        List<Token> tokens = new ArrayList<>();
        int nextOpaque = 0;

        int p = lexer.skipTrivia(0);
        while (p < source.length()) {
            // Skip past opaque ranges that are already behind us.
            while (nextOpaque < opaque.size() && opaque.get(nextOpaque).end() <= p) {
                nextOpaque++;
            }

            boolean atOpaque = nextOpaque < opaque.size() && opaque.get(nextOpaque).start() == p;
            if (atOpaque) {
                Opaque range = opaque.get(nextOpaque);
                String text = switch (range.kind()) {
                    case MARKUP -> MARKUP;
                    case STYLE -> STYLE_BLOCK;
                    case SCRIPT -> SCRIPT_BLOCK;
                };
                tokens.add(new Token(Kind.OPERATOR, text, p, range.end()));
                p = lexer.skipTrivia(range.end());
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

            // A style block is noted where it is. In a type body, it ends the member before it like a ';'.
            if (t.is(STYLE_BLOCK)) {
                styleSightings.add(new StyleSighting(t, innermostType(braces)));
                if (!isSkipped(braces.peek())) {
                    memberStart = i + 1;
                }
                continue;
            }
            // A script block is JavaScript: in a type body, it ends the member before it, as a style block does.
            if (t.is(SCRIPT_BLOCK)) {
                if (!isSkipped(braces.peek())) {
                    memberStart = i + 1;
                }
                continue;
            }

            // Inside a block: only track braces until the block closes.
            if (isSkipped(braces.peek())) {
                if (t.is("{")) {
                    braces.push(BLOCK);
                } else if (t.is("}")) {
                    Object closed = braces.pop();
                    boolean backInTypeBody = !isSkipped(braces.peek());
                    // A method body or an initializer block ends its member. The braces of a field
                    // initializer don't: the field goes on until its ';'.
                    if (backInTypeBody && closed == BLOCK) {
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
                afterImports = tokens.get(i).end();
                memberStart = i + 1;
            } else if (owner == null && t.is("import")) {
                i = readImport(i);
                afterImports = tokens.get(i).end();
                memberStart = i + 1;
            } else if (isTypeDeclaration(i)) {
                int bodyBrace = findTypeBodyBrace(i + 2);
                if (bodyBrace < 0) {
                    continue;
                }
                if (owner == null) {
                    recordTopLevelType(memberStart, i);
                }
                TypeDecl type = declareType(tokens.get(i + 1).text(), owner, bodyBrace);
                braces.push(type);
                i = bodyBrace;
                memberStart = i + 1;
            } else if (t.is("{")) {
                if (hasInitializer(memberStart, i)) {
                    braces.push(INITIALIZER_BRACE); // inside a field's initializer, not a body
                } else {
                    // A body starts: the member before it may be a method header.
                    if (owner == null) {
                        recordTopLevelBody(memberStart, i);
                    } else {
                        recordMethodIfHeader(memberStart, i, owner);
                    }
                    braces.push(BLOCK);
                }
            } else if (t.is(";")) {
                if (owner == null) {
                    recordTopLevelMemberWithoutBody(memberStart, i);
                }
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

    /** Whether a brace-stack entry is skipped over: a block, or a brace inside a field initializer. */
    private static boolean isSkipped(Object braceEntry) {
        return braceEntry == BLOCK || braceEntry == INITIALIZER_BRACE;
    }

    /**
     * Whether the member tokens {@code [from, to)} contain a {@code =} outside annotations, that is,
     * whether they are a field with an initializer. ({@code =} inside parentheses, as in
     * {@code @A(value = 1)}, never reaches here: the walk skips parenthesized tokens, but they are still in
     * the member's token range, so they are skipped again here.)
     */
    private boolean hasInitializer(int from, int to) {
        int depth = 0;
        for (int j = from; j < to; j++) {
            Token t = tokens.get(j);
            if (t.is("(")) {
                depth++;
            } else if (t.is(")")) {
                depth--;
            } else if (depth == 0 && t.is("=")) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Top-level declarations and the file class (semantics.md §9)
    // ---------------------------------------------------------------------------------------------

    /** A type declared at the top level; {@code keyword} is the index of {@code class}, {@code record}, ... */
    private void recordTopLevelType(int from, int keyword) {
        if (fileClassName == null) {
            return;
        }
        // For @interface, the declaration starts at the '@'.
        int declarationStart = keyword > from && tokens.get(keyword - 1).is("@") ? keyword - 1 : keyword;
        Modifiers modifiers = readModifiers(from, declarationStart);
        int start = tokens.get(from < declarationStart ? from : declarationStart).start();
        topLevel.add(new TopLevelDecl(TopLevelKind.TYPE, start, tokens.get(keyword).end(),
                tokens.get(declarationStart).start(), modifiers.names().contains("static")));
    }

    /**
     * The member {@code [from, to)} before a {@code {} at the top level: a method, or a constructor or an
     * initializer block, which are not allowed there.
     */
    private void recordTopLevelBody(int from, int to) {
        if (fileClassName == null) {
            return;
        }
        Modifiers modifiers = readModifiers(from, to);
        int start = tokens.get(from < to ? from : to).start();
        int headerEnd = from < to ? tokens.get(to - 1).end() : tokens.get(to).end();
        boolean isStatic = modifiers.names().contains("static");

        // Nothing but modifiers before the '{': {...} or static {...}.
        if (modifiers.next() >= to) {
            topLevel.add(new TopLevelDecl(TopLevelKind.INITIALIZER, start, headerEnd, start, isStatic));
            return;
        }

        int staticAt = tokens.get(modifiers.next()).start();
        boolean isMethod = recordMethodIfHeader(from, to, fileClass());
        TopLevelKind kind = isMethod ? TopLevelKind.METHOD : TopLevelKind.CONSTRUCTOR;
        topLevel.add(new TopLevelDecl(kind, start, headerEnd, staticAt, isStatic));
    }

    /** The member {@code [from, to)} before a {@code ;} at the top level: a field, or a method without a body. */
    private void recordTopLevelMemberWithoutBody(int from, int to) {
        if (fileClassName == null || from >= to) {
            return; // an empty declaration, ';', is allowed anywhere
        }
        Modifiers modifiers = readModifiers(from, to);
        if (modifiers.next() >= to) {
            return; // only modifiers: not a declaration javac would accept, so leave it to javac
        }
        boolean isField = hasInitializer(from, to) || !containsToken(from, to, "(");
        fileClass();
        topLevel.add(new TopLevelDecl(isField ? TopLevelKind.FIELD : TopLevelKind.METHOD,
                tokens.get(from).start(), tokens.get(to - 1).end(),
                tokens.get(modifiers.next()).start(), modifiers.names().contains("static")));
    }

    /** The file class, created when the first top-level field or method is found. */
    private TypeDecl fileClass() {
        if (fileClass == null) {
            String qualifiedName = packageName.isEmpty() ? fileClassName : packageName + "." + fileClassName;
            fileClass = new TypeDecl(fileClassName, null, qualifiedName, afterImports, fileLength);
            types.add(fileClass);
        }
        return fileClass;
    }

    /**
     * After the scan: if the file has a file class, the types declared at its top level are nested in it
     * (semantics.md §9.2), so their qualified names gain the file class's name: {@code app.Theme} becomes
     * {@code app.Layout.Theme}.
     */
    private void nestTopLevelTypesInFileClass() {
        if (fileClass == null) {
            return;
        }
        for (TypeDecl type : types) {
            if (type != fileClass && type.parent == null) {
                type.parent = fileClass;
            }
        }
        for (TypeDecl type : types) {
            type.qualifiedName = qualifiedNameOf(type);
        }
    }

    /** The innermost type body on the brace stack (skipping blocks), or null if there is none. */
    private static TypeDecl innermostType(Deque<Object> braces) {
        for (Object entry : braces) {
            if (entry instanceof TypeDecl type) {
                return type;
            }
        }
        return null;
    }

    /**
     * After the scan: the class each style block is in. Outside any type body (at the top level, or in a
     * top-level method) that is the file class, so a style block at the top level of a file without one
     * is in no class (semantics.md §10.1).
     */
    private void placeStyleBlocks() {
        for (StyleSighting sighting : styleSightings) {
            TypeDecl owner = sighting.owner() != null ? sighting.owner() : fileClass;
            styleBlocks.add(new StylePlace(sighting.token().start(), sighting.token().end(), owner));
        }
    }

    private String qualifiedNameOf(TypeDecl type) {
        if (type.parent != null) {
            return qualifiedNameOf(type.parent) + "." + type.name;
        }
        return packageName.isEmpty() ? type.name : packageName + "." + type.name;
    }

    private boolean containsToken(int from, int to, String text) {
        for (int j = from; j < to; j++) {
            if (tokens.get(j).is(text)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Package and imports
    // ---------------------------------------------------------------------------------------------

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
     * enum constants don't match and are ignored. Returns whether a method was recorded.
     *
     * <p>A method directly in the file class is a top-level method, which is static even when it isn't
     * declared so (semantics.md §9.2).
     */
    private boolean recordMethodIfHeader(int from, int to, TypeDecl owner) {
        if (owner == null || from >= to) {
            return false;
        }

        // Leading annotations and modifiers.
        Modifiers leading = readModifiers(from, to);
        List<Annotation> annotations = leading.annotations();
        Set<String> modifiers = new LinkedHashSet<>(leading.names());
        if (owner == fileClass) {
            modifiers.add("static");
        }
        int k = leading.next();

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
                return false;
            }
            angleDepth += angleBracketDelta(t);
            if (angleDepth <= 0 && t.is("(")) {
                openParen = j;
                break;
            }
        }
        if (openParen < 0) {
            return false;
        }

        // The name is the identifier before '('; the return type is everything between the modifiers and
        // the name. No return type means a constructor.
        int nameIndex = openParen - 1;
        boolean hasName = nameIndex >= k && tokens.get(nameIndex).kind() == Kind.IDENTIFIER;
        boolean hasReturnType = nameIndex > k;
        if (!hasName || !hasReturnType) {
            return false;
        }

        int closeParen = matchingParen(openParen, to);
        if (closeParen < 0) {
            return false;
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
        return true;
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

    /**
     * The annotations and modifiers at the start of a declaration.
     *
     * @param next the index of the first token after them
     */
    private record Modifiers(List<Annotation> annotations, Set<String> names, int next) {}

    /** Reads the annotations and modifiers that start the tokens {@code [from, to)}. */
    private Modifiers readModifiers(int from, int to) {
        List<Annotation> annotations = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        int k = from;
        while (k < to) {
            Token t = tokens.get(k);
            if (isAnnotationStart(k, to)) {
                k = readAnnotation(k, to, annotations);
            } else if (t.kind() == Kind.IDENTIFIER && MODIFIERS.contains(t.text())) {
                names.add(t.text());
                k++;
            } else if (isNonSealed(k, to)) {
                k += 3; // "non", "-", "sealed"
            } else {
                break;
            }
        }
        return new Modifiers(annotations, names, k);
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
