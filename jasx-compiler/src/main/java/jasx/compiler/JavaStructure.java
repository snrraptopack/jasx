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
 * The declarations of a source file that the compiler needs, found by tokenizing only (semantics.md §5.3):
 * the package, the imports, the named types with their body ranges, and the method headers declared
 * directly in those types. Markup is skipped, and method bodies are never examined.
 */
final class JavaStructure {
    record Import(boolean isStatic, String name, boolean onDemand) {}

    record Annotation(String name, List<Token> arguments, int start, int end) {}

    record Param(List<Annotation> annotations, String type, String name, int start, int end) {}

    record Method(TypeDecl owner, List<Annotation> annotations, Set<String> modifiers, String returnType,
            String name, int nameStart, int nameEnd, List<Param> params, int headerStart) {}

    /** A named class, interface, enum, record or annotation type, with the range of its body braces. */
    static final class TypeDecl {
        final String name;
        final TypeDecl parent;
        final String qualifiedName;
        final int bodyStart;
        int bodyEnd;

        TypeDecl(String name, TypeDecl parent, String qualifiedName, int bodyStart, int bodyEnd) {
            this.name = name;
            this.parent = parent;
            this.qualifiedName = qualifiedName;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
        }
    }

    private static final String MARKUP = "<jasx>";
    private static final Object BLOCK = new Object();
    private static final Set<String> MODIFIERS = Set.of(
            "public", "protected", "private", "static", "final", "abstract", "synchronized", "native",
            "strictfp", "default", "transient", "volatile", "sealed");
    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "interface", "enum", "record");

    private final List<Token> tokens;
    private final int length;
    String packageName = "";
    final List<Import> imports = new ArrayList<>();
    final List<TypeDecl> types = new ArrayList<>();
    final List<Method> methods = new ArrayList<>();

    private JavaStructure(List<Token> tokens, int length) {
        this.tokens = tokens;
        this.length = length;
    }

    /** Scans {@code source}, treating each {@code [start, end)} range in {@code markup} as one opaque token. */
    static JavaStructure scan(String source, List<int[]> markup) {
        JavaLexer lexer = new JavaLexer(source);
        List<Token> tokens = new ArrayList<>();
        int next = 0;
        int p = lexer.skipTrivia(0);
        while (p < source.length()) {
            while (next < markup.size() && markup.get(next)[1] <= p) {
                next++;
            }
            if (next < markup.size() && markup.get(next)[0] == p) {
                int end = markup.get(next)[1];
                tokens.add(new Token(Kind.OPERATOR, MARKUP, p, end));
                p = lexer.skipTrivia(end);
                continue;
            }
            Token t = lexer.lex(p);
            tokens.add(t);
            p = lexer.skipTrivia(t.end());
        }
        JavaStructure structure = new JavaStructure(tokens, source.length());
        structure.run();
        return structure;
    }

    /** The innermost named type whose body contains {@code offset}, or null. */
    TypeDecl enclosingType(int offset) {
        TypeDecl best = null;
        for (TypeDecl t : types) {
            if (t.bodyStart <= offset && offset < t.bodyEnd && (best == null || t.bodyStart > best.bodyStart)) {
                best = t;
            }
        }
        return best;
    }

    private void run() {
        Deque<Object> frames = new ArrayDeque<>();
        int memberStart = 0;
        int parens = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            Object top = frames.peek();
            if (top == BLOCK) {
                if (t.is("{")) {
                    frames.push(BLOCK);
                } else if (t.is("}")) {
                    frames.pop();
                    if (frames.peek() != BLOCK) {
                        memberStart = i + 1;
                        parens = 0;
                    }
                }
                continue;
            }
            TypeDecl owner = (TypeDecl) top;
            if (t.is("(")) {
                parens++;
                continue;
            }
            if (t.is(")")) {
                parens = Math.max(0, parens - 1);
                continue;
            }
            if (parens > 0) {
                continue;
            }
            if (owner == null && t.is("package")) {
                int end = indexOf(";", i + 1);
                packageName = join(i + 1, end);
                i = end;
                memberStart = i + 1;
            } else if (owner == null && t.is("import")) {
                int j = i + 1;
                boolean isStatic = j < tokens.size() && tokens.get(j).is("static");
                if (isStatic) {
                    j++;
                }
                int end = indexOf(";", j);
                String name = join(j, end);
                boolean onDemand = name.endsWith(".*");
                imports.add(new Import(isStatic, onDemand ? name.substring(0, name.length() - 2) : name, onDemand));
                i = end;
                memberStart = i + 1;
            } else if (isTypeDeclaration(i)) {
                int body = typeBodyStart(i + 2);
                if (body < 0) {
                    continue;
                }
                String name = tokens.get(i + 1).text();
                String qualified = owner != null ? owner.qualifiedName + "." + name
                        : packageName.isEmpty() ? name : packageName + "." + name;
                TypeDecl type = new TypeDecl(name, owner, qualified, tokens.get(body).start(), length);
                types.add(type);
                frames.push(type);
                i = body;
                memberStart = i + 1;
            } else if (t.is("{")) {
                analyzeMember(memberStart, i, owner);
                frames.push(BLOCK);
            } else if (t.is(";")) {
                memberStart = i + 1;
            } else if (t.is("}")) {
                if (owner != null) {
                    owner.bodyEnd = t.end();
                    frames.pop();
                }
                memberStart = i + 1;
            }
        }
    }

    private boolean isTypeDeclaration(int i) {
        Token t = tokens.get(i);
        return t.kind() == Kind.IDENTIFIER && TYPE_KEYWORDS.contains(t.text())
                && (i == 0 || !tokens.get(i - 1).is("."))
                && i + 1 < tokens.size() && tokens.get(i + 1).kind() == Kind.IDENTIFIER;
    }

    /** The index of the '{' that opens a type body, skipping type parameters, record headers and clauses. */
    private int typeBodyStart(int from) {
        int depth = 0;
        for (int j = from; j < tokens.size(); j++) {
            Token t = tokens.get(j);
            depth += angleDelta(t);
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

    /** Records a method declared by the tokens {@code [from, to)} that precede a '{', if they are a method header. */
    private void analyzeMember(int from, int to, TypeDecl owner) {
        if (owner == null || from >= to) {
            return;
        }
        List<Annotation> annotations = new ArrayList<>();
        Set<String> modifiers = new LinkedHashSet<>();
        int k = from;
        while (k < to) {
            Token t = tokens.get(k);
            if (t.is("@") && k + 1 < to && tokens.get(k + 1).kind() == Kind.IDENTIFIER && !tokens.get(k + 1).is("interface")) {
                k = annotation(k, to, annotations);
            } else if (t.kind() == Kind.IDENTIFIER && MODIFIERS.contains(t.text())) {
                modifiers.add(t.text());
                k++;
            } else if (t.is("non") && k + 2 < to && tokens.get(k + 1).is("-") && tokens.get(k + 2).is("sealed")) {
                k += 3;
            } else {
                break;
            }
        }
        if (k < to && tokens.get(k).is("<")) {
            k = skipAngles(k, to);
        }
        int open = -1;
        int angles = 0;
        for (int j = k; j < to; j++) {
            Token t = tokens.get(j);
            if (t.is("=")) {
                return;
            }
            angles += angleDelta(t);
            if (angles <= 0 && t.is("(")) {
                open = j;
                break;
            }
        }
        if (open <= k || tokens.get(open - 1).kind() != Kind.IDENTIFIER || open - 1 == k) {
            return; // not a method, or a constructor (no return type)
        }
        int close = matchingParen(open, to);
        if (close < 0) {
            return;
        }
        Token name = tokens.get(open - 1);
        methods.add(new Method(owner, List.copyOf(annotations), modifiers, typeText(k, open - 1), name.text(),
                name.start(), name.end(), params(open + 1, close), tokens.get(from).start()));
    }

    private List<Param> params(int from, int to) {
        List<Param> params = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int j = from; j <= to; j++) {
            if (j == to || (depth == 0 && tokens.get(j).is(","))) {
                Param p = param(start, j);
                if (p != null) {
                    params.add(p);
                }
                start = j + 1;
                continue;
            }
            Token t = tokens.get(j);
            depth += angleDelta(t);
            if (t.is("(") || t.is("[") || t.is("{")) {
                depth++;
            } else if (t.is(")") || t.is("]") || t.is("}")) {
                depth--;
            }
        }
        return params;
    }

    private Param param(int from, int to) {
        if (from >= to) {
            return null;
        }
        List<Annotation> annotations = new ArrayList<>();
        int j = from;
        while (j < to) {
            if (tokens.get(j).is("@") && j + 1 < to && tokens.get(j + 1).kind() == Kind.IDENTIFIER) {
                j = annotation(j, to, annotations);
            } else if (tokens.get(j).is("final")) {
                j++;
            } else {
                break;
            }
        }
        int end = to;
        StringBuilder dims = new StringBuilder();
        while (end - 2 >= j && tokens.get(end - 1).is("]") && tokens.get(end - 2).is("[")) {
            dims.append("[]");
            end -= 2;
        }
        if (end - 1 <= j || tokens.get(end - 1).kind() != Kind.IDENTIFIER) {
            return null;
        }
        return new Param(List.copyOf(annotations), typeText(j, end - 1) + dims, tokens.get(end - 1).text(),
                tokens.get(from).start(), tokens.get(to - 1).end());
    }

    /** Parses the annotation at {@code k} into {@code out}; returns the index after it. */
    private int annotation(int k, int limit, List<Annotation> out) {
        int start = tokens.get(k).start();
        int j = k + 1;
        StringBuilder name = new StringBuilder(tokens.get(j++).text());
        while (j + 1 < limit && tokens.get(j).is(".") && tokens.get(j + 1).kind() == Kind.IDENTIFIER) {
            name.append('.').append(tokens.get(j + 1).text());
            j += 2;
        }
        List<Token> arguments = List.of();
        int end = tokens.get(j - 1).end();
        if (j < limit && tokens.get(j).is("(")) {
            int close = matchingParen(j, limit);
            if (close < 0) {
                close = limit - 1;
            }
            arguments = List.copyOf(tokens.subList(j + 1, close));
            end = tokens.get(close).end();
            j = close + 1;
        }
        out.add(new Annotation(name.toString(), arguments, start, end));
        return j;
    }

    private int skipAngles(int k, int limit) {
        int depth = 0;
        for (int j = k; j < limit; j++) {
            depth += angleDelta(tokens.get(j));
            if (depth <= 0) {
                return j + 1;
            }
        }
        return limit;
    }

    private int matchingParen(int open, int limit) {
        int depth = 0;
        for (int j = open; j < limit; j++) {
            if (tokens.get(j).is("(")) {
                depth++;
            } else if (tokens.get(j).is(")") && --depth == 0) {
                return j;
            }
        }
        return -1;
    }

    private static int angleDelta(Token t) {
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

    private int indexOf(String text, int from) {
        for (int j = from; j < tokens.size(); j++) {
            if (tokens.get(j).is(text)) {
                return j;
            }
        }
        return tokens.size() - 1;
    }

    private String join(int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int j = from; j < to && j < tokens.size(); j++) {
            out.append(tokens.get(j).text());
        }
        return out.toString();
    }

    /** The text of a type, with a space only between adjacent words ({@code ? extends T}). */
    private String typeText(int from, int to) {
        StringBuilder out = new StringBuilder();
        Token prev = null;
        for (int j = from; j < to; j++) {
            Token t = tokens.get(j);
            if (prev != null && isWord(prev) && isWord(t)) {
                out.append(' ');
            }
            out.append(t.text());
            prev = t;
        }
        return out.toString();
    }

    private static boolean isWord(Token t) {
        return t.kind() == Kind.IDENTIFIER || t.is("?");
    }
}
