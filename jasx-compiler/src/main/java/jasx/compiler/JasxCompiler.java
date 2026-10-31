package jasx.compiler;

import java.util.ArrayList;
import java.util.List;
import jasx.compiler.ComponentIndex.Children;
import jasx.compiler.ComponentIndex.Component;
import jasx.compiler.ComponentIndex.Prop;
import jasx.compiler.ComponentIndex.PropKind;
import jasx.parser.Diagnostic;
import jasx.parser.JasxParser;
import jasx.parser.JavaLexer;
import jasx.parser.LineMap;
import jasx.parser.ParseResult;
import jasx.parser.ast.Ast.Markup;
import jasx.parser.ast.Ast.Segment;

/**
 * Compiles {@code .jasx} files to Java with factory lowering (semantics.md §5, §6). {@code .java} files
 * passed alongside are scanned for {@code @jasx.Component} methods but not rewritten.
 */
public final class JasxCompiler {
    private final Target target;
    private final ComponentIndex dependencies;

    /** @param dependencies components of the classpath, usually {@link ComponentIndex#fromClasspath} */
    public JasxCompiler(Target target, ComponentIndex dependencies) {
        this.target = target;
        this.dependencies = dependencies;
    }

    public JasxCompiler(Target target) {
        this(target, new ComponentIndex());
    }

    private record Unit(SourceFile file, LineMap lines, ParseResult parsed, JavaStructure structure,
            List<Integer> annotations) {}

    public CompilationResult compile(List<SourceFile> sources) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        ComponentIndex local = new ComponentIndex();
        List<Unit> units = new ArrayList<>();
        for (SourceFile file : sources) {
            LineMap lines = new LineMap(file.content());
            ParseResult parsed = null;
            List<int[]> markup = new ArrayList<>();
            if (file.isJasx()) {
                parsed = JasxParser.parse(file.content(), file.path());
                parsed.diagnostics().forEach(d -> diagnostics.add(new CompilerDiagnostic(file.path(), d, lines)));
                for (Segment s : parsed.unit().segments()) {
                    if (s instanceof Markup) {
                        markup.add(new int[] {s.start(), s.end()});
                    }
                }
            }
            JavaStructure structure = JavaStructure.scan(file.content(), markup);
            List<Integer> annotations = new ArrayList<>();
            List<Diagnostic> indexErrors = new ArrayList<>();
            indexComponents(file, structure, local, annotations, indexErrors);
            indexErrors.forEach(d -> diagnostics.add(new CompilerDiagnostic(file.path(), d, lines)));
            units.add(new Unit(file, lines, parsed, structure, annotations));
        }

        ComponentIndex index = local.withFallback(dependencies);
        List<GeneratedFile> files = new ArrayList<>();
        for (Unit unit : units) {
            if (unit.parsed() == null || unit.parsed().hasErrors()) {
                continue;
            }
            Lowering lowering = new Lowering(unit.file(), unit.lines(), target, new Resolver(unit.structure(), index));
            GeneratedFile generated = lowering.lower(unit.parsed().unit(), unit.annotations());
            lowering.diagnostics().forEach(d -> diagnostics.add(new CompilerDiagnostic(unit.file().path(), d, unit.lines())));
            if (lowering.diagnostics().stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR)) {
                files.add(generated);
            }
        }
        return new CompilationResult(files, diagnostics, local);
    }

    /** Adds the components declared in {@code file} to {@code index} (semantics.md §5.1–§5.3). */
    private static void indexComponents(SourceFile file, JavaStructure structure, ComponentIndex index,
            List<Integer> annotations, List<Diagnostic> errors) {
        for (JavaStructure.Method m : structure.methods) {
            JavaStructure.Annotation marker = annotation(m.annotations(), "Component", structure);
            boolean valid = m.modifiers().contains("static")
                    && (m.returnType().equals("Element") || m.returnType().equals("jasx.runtime.Element"))
                    && !Character.isLowerCase(m.name().charAt(0));
            if (marker != null && !valid) {
                errors.add(Diagnostic.error("JASX2010", "@jasx.Component requires a static method that returns"
                        + " Element and whose name does not start with a lowercase letter", marker.start(), marker.end()));
                continue;
            }
            if (!valid || (marker == null && !file.isJasx())) {
                continue;
            }
            List<Prop> params = new ArrayList<>();
            Children children = Children.NONE;
            for (JavaStructure.Param p : m.params()) {
                String type = p.type();
                if (p.name().equals("children") && (type.equals("Element") || type.equals("jasx.runtime.Element"))) {
                    children = Children.SINGLE;
                    params.add(new Prop(p.name(), type, PropKind.CHILDREN, null));
                } else if (p.name().equals("children") && isElementList(type)) {
                    children = Children.LIST;
                    params.add(new Prop(p.name(), type, PropKind.CHILDREN, null));
                } else {
                    JavaStructure.Annotation def = annotation(p.annotations(), "Default", structure);
                    if (def != null) {
                        String literal = defaultLiteral(def);
                        if (literal == null) {
                            errors.add(Diagnostic.error("JASX2011", "the value of @jasx.Default must be the source"
                                    + " text of a single Java literal, such as \"\\\"text\\\"\" or \"5\"", def.start(), def.end()));
                        }
                        params.add(new Prop(p.name(), type, literal != null ? PropKind.DEFAULT : PropKind.OPTIONAL, literal));
                    } else if (annotation(p.annotations(), "Optional", structure) != null) {
                        params.add(new Prop(p.name(), type, PropKind.OPTIONAL, null));
                    } else {
                        params.add(new Prop(p.name(), type, PropKind.REQUIRED, null));
                    }
                }
            }
            Component component = new Component(m.owner().qualifiedName, m.name(), List.copyOf(params), children);
            if (!index.add(component)) {
                errors.add(Diagnostic.error("JASX2006", "component " + m.name() + " is already declared in "
                        + m.owner().qualifiedName + "; components cannot be overloaded", m.nameStart(), m.nameEnd()));
            } else if (file.isJasx() && marker == null) {
                annotations.add(m.headerStart());
            }
        }
    }

    private static boolean isElementList(String type) {
        return type.equals("List<Element>") || type.equals("java.util.List<Element>")
                || type.equals("List<jasx.runtime.Element>") || type.equals("java.util.List<jasx.runtime.Element>");
    }

    /** The annotation {@code jasx.<simpleName>} among {@code annotations}, written fully qualified or imported. */
    private static JavaStructure.Annotation annotation(List<JavaStructure.Annotation> annotations, String simpleName,
            JavaStructure structure) {
        for (JavaStructure.Annotation a : annotations) {
            if (a.name().equals("jasx." + simpleName)) {
                return a;
            }
            if (a.name().equals(simpleName) && structure.imports.stream().anyMatch(i -> !i.isStatic()
                    && ((!i.onDemand() && i.name().equals("jasx." + simpleName)) || (i.onDemand() && i.name().equals("jasx"))))) {
                return a;
            }
        }
        return null;
    }

    /** The literal of {@code @Default("literal")} (semantics.md §5.2), or null if it is not a single literal. */
    private static String defaultLiteral(JavaStructure.Annotation a) {
        List<JavaLexer.Token> args = a.arguments();
        JavaLexer.Token value;
        if (args.size() == 1) {
            value = args.get(0);
        } else if (args.size() == 3 && args.get(0).is("value") && args.get(1).is("=")) {
            value = args.get(2);
        } else {
            return null;
        }
        if (value.kind() != JavaLexer.Kind.STRING) {
            return null;
        }
        String literal = decodeString(value.text());
        if (literal == null) {
            return null;
        }
        List<JavaLexer.Token> tokens = new JavaLexer(literal).tokens(0, literal.length());
        boolean single = tokens.size() == 1 && (isCompleteLiteral(tokens.get(0)) || tokens.get(0).is("true") || tokens.get(0).is("false"));
        boolean negative = tokens.size() == 2 && tokens.get(0).is("-") && tokens.get(1).kind() == JavaLexer.Kind.NUMBER;
        return single || negative ? literal.strip() : null;
    }

    private static boolean isCompleteLiteral(JavaLexer.Token t) {
        return switch (t.kind()) {
            case NUMBER -> true;
            case STRING, CHAR -> t.text().length() >= 2 && t.text().endsWith(t.text().substring(0, 1));
            case TEXT_BLOCK -> t.text().length() >= 6 && t.text().endsWith("\"\"\"");
            default -> false;
        };
    }

    /** The value of a Java string literal (Unicode escapes and escape sequences processed), or null. */
    private static String decodeString(String raw) {
        JavaLexer chars = new JavaLexer(raw);
        StringBuilder out = new StringBuilder();
        int p = chars.next(0);
        while (p < raw.length()) {
            int c = chars.charAt(p);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append((char) c);
                p = chars.next(p);
                continue;
            }
            p = chars.next(p);
            int e = chars.charAt(p);
            switch (e) {
                case 'b' -> out.append('\b');
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'f' -> out.append('\f');
                case 'r' -> out.append('\r');
                case 's' -> out.append(' ');
                case '"', '\'', '\\' -> out.append((char) e);
                default -> {
                    if (e < '0' || e > '7') {
                        return null;
                    }
                    int v = 0;
                    int max = e <= '3' ? 3 : 2;
                    for (int n = 0; n < max && chars.charAt(p) >= '0' && chars.charAt(p) <= '7'; n++) {
                        v = v * 8 + chars.charAt(p) - '0';
                        p = chars.next(p);
                    }
                    out.append((char) v);
                    continue;
                }
            }
            p = chars.next(p);
        }
        return null;
    }
}
