package jasx.lsp;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import jasx.compiler.GeneratedFile;
import jasx.compiler.SpanMap;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * The outline of a {@code .jasx} file ({@code textDocument/documentSymbol}): its classes, components, methods
 * and fields, nested as they are declared, for the editor's Structure view, Outline and breadcrumbs.
 *
 * <p>javac only parses the file's generated Java (no classpath, no analysis), so this is quick enough to run
 * as often as editors ask. Declarations are kept only if their name was copied from the {@code .jasx} file,
 * and their ranges are mapped back to it. A file class (semantics.md §9) is code JASX wrote around the file,
 * not a declaration in it, so its members are the top level of the outline.
 */
final class Outline {
    private Outline() {}

    /** LSP SymbolKind values. */
    private static final int KIND_CLASS = 5;
    private static final int KIND_METHOD = 6;
    private static final int KIND_FIELD = 8;
    private static final int KIND_ENUM = 10;
    private static final int KIND_INTERFACE = 11;
    private static final int KIND_FUNCTION = 12;
    private static final int KIND_STRUCT = 23;

    /**
     * One declaration: {@code [start, end)} is the whole declaration, {@code [nameStart, nameEnd)} its name,
     * both in the {@code .jasx} file's text.
     */
    record Symbol(String name, String detail, int kind, int start, int end, int nameStart, int nameEnd,
            List<Symbol> children) {}

    /**
     * The outline of the file {@code generated} came from.
     *
     * @param componentNames where the file's components' names start, in the {@code .jasx} file: those
     *                       methods are shown as components
     */
    static List<Symbol> of(GeneratedFile generated, Set<Integer> componentNames) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager files = javac.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///Outline.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return generated.content();
            }
        };
        JavacTask task = (JavacTask) javac.getTask(null, files, diagnostic -> {}, List.of("-proc:none"), null, List.of(unit));
        try {
            List<Symbol> symbols = new ArrayList<>();
            for (CompilationUnitTree tree : task.parse()) {
                Reader reader = new Reader(tree, Trees.instance(task).getSourcePositions(), generated, componentNames);
                for (Tree declaration : tree.getTypeDecls()) {
                    reader.add(declaration, symbols);
                }
            }
            return symbols;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** Turns declarations of one parsed file into symbols. */
    private record Reader(CompilationUnitTree unit, SourcePositions positions, GeneratedFile generated,
            Set<Integer> componentNames) {

        /** Adds the symbol for {@code tree} to {@code into}, or its members if JASX wrote it (a file class). */
        void add(Tree tree, List<Symbol> into) {
            switch (tree) {
                case ClassTree type -> addClass(type, into);
                case MethodTree method -> addMethod(method, into);
                case VariableTree field -> addField(field, into);
                default -> {} // initializers, empty declarations
            }
        }

        private void addClass(ClassTree type, List<Symbol> into) {
            List<Symbol> members = new ArrayList<>();
            for (Tree member : type.getMembers()) {
                add(member, members);
            }
            String name = type.getSimpleName().toString();
            long from = positions.getEndPosition(unit, type.getModifiers()) > 0
                    ? positions.getEndPosition(unit, type.getModifiers()) : positions.getStartPosition(unit, type);
            Symbol symbol = symbol(type, name, null, kindOf(type), from, members);
            if (symbol == null) {
                into.addAll(members); // a file class: its members are the file's top level
            } else {
                into.add(symbol);
            }
        }

        private void addMethod(MethodTree method, List<Symbol> into) {
            boolean constructor = method.getName().contentEquals("<init>");
            if (constructor || method.getReturnType() == null) {
                return; // constructors are rare in .jasx files, and a default one has no source
            }
            String name = method.getName().toString();
            StringJoiner parameters = new StringJoiner(", ", "(", ")");
            for (VariableTree parameter : method.getParameters()) {
                parameters.add(parameter.getType() + " " + parameter.getName());
            }
            String detail = parameters + " " + method.getReturnType();
            Symbol symbol = symbol(method, name, detail, KIND_METHOD,
                    positions.getEndPosition(unit, method.getReturnType()), List.of());
            if (symbol != null && componentNames.contains(symbol.nameStart())) {
                symbol = new Symbol(name, "component " + detail, KIND_FUNCTION, symbol.start(), symbol.end(),
                        symbol.nameStart(), symbol.nameEnd(), List.of());
            }
            if (symbol != null) {
                into.add(symbol);
            }
        }

        private void addField(VariableTree field, List<Symbol> into) {
            String name = field.getName().toString();
            long from = field.getType() != null ? positions.getEndPosition(unit, field.getType())
                    : positions.getStartPosition(unit, field);
            Symbol symbol = symbol(field, name, String.valueOf(field.getType()), KIND_FIELD, from, List.of());
            if (symbol != null) {
                into.add(symbol);
            }
        }

        /**
         * The symbol of a declaration whose name is the first whole word {@code name} after {@code nameFrom}, or
         * null if that name wasn't copied from the {@code .jasx} file (JASX wrote the declaration, or it is
         * one of JASX's own names, which contain {@code $}).
         */
        private Symbol symbol(Tree tree, String name, String detail, int kind, long nameFrom, List<Symbol> children) {
            SpanMap spans = generated.spanMap();
            String text = generated.content();
            long nameAt = nameFrom >= 0 ? JavaNames.wordAt(text, name, (int) nameFrom) : -1;
            boolean copied = nameAt >= 0 && spans.isVerbatim((int) nameAt) && spans.isVerbatim((int) nameAt + name.length() - 1);
            if (!copied || name.contains("$")) {
                return null;
            }
            int nameStart = spans.toSource((int) nameAt);
            int start = Math.min(spans.toSource((int) positions.getStartPosition(unit, tree)), nameStart);
            int end = Math.max(spans.toSource((int) positions.getEndPosition(unit, tree)), nameStart + name.length());
            return new Symbol(name, detail, kind, start, end, nameStart, nameStart + name.length(), children);
        }

        private static int kindOf(ClassTree type) {
            return switch (type.getKind()) {
                case INTERFACE, ANNOTATION_TYPE -> KIND_INTERFACE;
                case ENUM -> KIND_ENUM;
                case RECORD -> KIND_STRUCT;
                default -> KIND_CLASS;
            };
        }
    }
}
