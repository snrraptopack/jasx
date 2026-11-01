package jasx.lsp;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.source.tree.Scope;
import jasx.compiler.CompilationResult;
import jasx.compiler.ComponentIndex;
import jasx.compiler.GeneratedFile;
import jasx.compiler.JasxCompiler;
import jasx.compiler.SourceFile;
import jasx.compiler.Target;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Completion in Java code of a {@code .jasx} file: after {@code e.} the members of {@code e}'s type, and
 * elsewhere the names in scope (locals, parameters, fields, methods, static imports).
 *
 * <p><b>How.</b> A {@code .jasx} file is not Java, but its lowered form is, so the JDK's own compiler answers
 * the question on that:
 *
 * <ol>
 *   <li>the name being typed is replaced with a marker identifier, and the group is compiled with JASX, so that
 *       the marker lands in the generated Java exactly where the cursor is, with the same surroundings. A
 *       handler in markup is a lambda there, passed to the target's builder, so javac knows that the {@code e}
 *       of {@code onclick={e -> e.}} is a {@code MouseEvent};
 *   <li>javac parses and analyses the generated Java with the build's classpath (a reference to the marker is
 *       an error, which is ignored), and the tree API gives the type of the expression before the dot, or the
 *       scope at the marker.
 * </ol>
 *
 * <p>Names javac or JASX made up (they contain {@code $}) are not offered. Without build information, the
 * server's own classpath is used: the JDK, the JASX runtime and the bundled target.
 */
final class JavaCompletions {
    private JavaCompletions() {}

    /** Stands for the name being typed; a valid identifier no program uses. */
    static final String MARKER = "jasxCompletionMarker";

    /** LSP CompletionItemKind values. */
    private static final int KIND_METHOD = 2;
    private static final int KIND_FIELD = 5;
    private static final int KIND_VARIABLE = 6;
    private static final int KIND_CLASS = 7;
    private static final int KIND_INTERFACE = 8;
    private static final int KIND_ENUM_MEMBER = 20;

    /**
     * The suggestions at {@code offset} in the file {@code path} of {@code group}.
     *
     * @param group     the texts of the group's files ({@code .jasx} and {@code .java}), the editor's if open
     * @param classpath the classpath for javac
     * @param release   the Java release the build compiles for, or null
     */
    static Completions.Result at(Map<String, String> group, String path, int offset, Target target,
            ComponentIndex dependencies, String classpath, Integer release) {
        String text = group.get(path);
        int nameStart = identifierStart(text, offset);
        int nameEnd = identifierEnd(text, offset);
        // The marker is a call, so that a lambda being typed stays valid for javac: e -> e.marker() is a
        // statement, which fits a handler that returns nothing; e -> e.marker would not, and javac would then
        // lose e's type. If a call follows already (e.va|lue()), it is the call.
        boolean callFollows = text.substring(nameEnd).stripLeading().startsWith("(");
        String marked = text.substring(0, nameStart) + MARKER + (callFollows ? "" : "()") + text.substring(nameEnd);

        List<SourceFile> jasx = new ArrayList<>();
        List<SourceFile> java = new ArrayList<>();
        group.forEach((relative, content) -> {
            SourceFile source = new SourceFile(relative, relative.equals(path) ? marked : content);
            (relative.endsWith(".java") ? java : jasx).add(source);
        });
        CompilationResult lowered = new JasxCompiler(target, dependencies).compile(jasx);
        GeneratedFile generated = lowered.files().stream()
                .filter(file -> file.source().path().equals(path))
                .findFirst().orElse(null);
        boolean markerArrived = generated != null && generated.content().contains(MARKER);
        if (!markerArrived) {
            return Completions.Result.NONE; // the file couldn't be lowered (a markup syntax error, say)
        }
        try {
            List<Completions.Item> items = analyse(lowered.files(), java, path, classpath, release);
            return new Completions.Result(nameStart, items);
        } catch (RuntimeException e) {
            return Completions.Result.NONE; // javac couldn't analyse it: no suggestions rather than an error
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Running javac
    // ---------------------------------------------------------------------------------------------

    private static List<Completions.Item> analyse(List<GeneratedFile> generated, List<SourceFile> java, String path,
            String classpath, Integer release) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<JavaFileObject> units = new ArrayList<>();
        JavaFileObject target = null;
        for (GeneratedFile file : generated) {
            JavaFileObject unit = inMemory(file.javaPath(), file.content());
            units.add(unit);
            if (file.source().path().equals(path)) {
                target = unit;
            }
        }
        for (SourceFile file : java) {
            units.add(inMemory(file.path(), file.content()));
        }

        List<String> options = new ArrayList<>(List.of("-classpath", classpath, "-proc:none", "-encoding", "UTF-8",
                "-nowarn", "-implicit:none",
                // Keep analysing after errors: the marker itself is one.
                "-XDshould-stop.ifError=FLOW"));
        if (release != null) {
            options.addAll(List.of("--release", release.toString()));
        }
        StandardJavaFileManager files = javac.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
        JavacTask task = (JavacTask) javac.getTask(null, files, diagnostic -> {}, options, null, units);
        try {
            Iterable<? extends CompilationUnitTree> trees = task.parse();
            task.analyze();
            for (CompilationUnitTree unit : trees) {
                if (target != null && unit.getSourceFile().toUri().equals(target.toUri())) { // javac wraps file objects
                    return suggestionsAt(task, unit);
                }
            }
            return List.of();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    private static JavaFileObject inMemory(String path, String content) {
        URI uri = URI.create("string:///" + path.replace('\\', '/').replace(" ", "%20"));
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }

    /** The marker's tree: an identifier, or the name of a member select ({@code e.marker}). */
    private static TreePath findMarker(CompilationUnitTree unit) {
        TreePath[] found = new TreePath[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree tree, Void unused) {
                if (tree.getName().contentEquals(MARKER)) {
                    found[0] = getCurrentPath();
                }
                return super.visitIdentifier(tree, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
                if (tree.getIdentifier().contentEquals(MARKER)) {
                    found[0] = getCurrentPath();
                }
                return super.visitMemberSelect(tree, unused);
            }
        }.scan(unit, null);
        return found[0];
    }

    // ---------------------------------------------------------------------------------------------
    // What to suggest
    // ---------------------------------------------------------------------------------------------

    private static List<Completions.Item> suggestionsAt(JavacTask task, CompilationUnitTree unit) {
        TreePath marker = findMarker(unit);
        if (marker == null) {
            return List.of(); // the cursor was in a string or a comment
        }
        Suggestions suggestions = new Suggestions(task, Trees.instance(task).getScope(marker));
        if (marker.getLeaf() instanceof MemberSelectTree select) {
            suggestions.membersOf(new TreePath(marker, select.getExpression()));
        } else {
            suggestions.namesInScope(unit);
        }
        return suggestions.items();
    }

    /** The suggestions being collected, without duplicates, in a scope. */
    private static final class Suggestions {
        private final Trees trees;
        private final Elements elements;
        private final Types types;
        private final Scope scope;
        private final Map<String, Completions.Item> items = new LinkedHashMap<>();

        Suggestions(JavacTask task, Scope scope) {
            this.trees = Trees.instance(task);
            this.elements = task.getElements();
            this.types = task.getTypes();
            this.scope = scope;
        }

        List<Completions.Item> items() {
            return List.copyOf(items.values());
        }

        /** After a dot: the members of the expression's type, or the static members of a type name. */
        void membersOf(TreePath expression) {
            TypeMirror type = trees.getTypeMirror(expression);
            Element element = trees.getElement(expression);
            if (type == null || type.getKind() == TypeKind.ERROR) {
                return;
            }
            boolean isTypeName = element != null && (element.getKind().isClass() || element.getKind().isInterface());
            if (type instanceof ArrayType) {
                add("length", KIND_FIELD, "int", "length", "0");
                type = elements.getTypeElement("java.lang.Object").asType();
            }
            if (!(type instanceof DeclaredType declared)) {
                return;
            }
            TypeElement typeElement = (TypeElement) declared.asElement();
            for (Element member : elements.getAllMembers(typeElement)) {
                boolean isStatic = member.getModifiers().contains(Modifier.STATIC);
                boolean fits = isTypeName ? isStatic || member.getKind().isClass() || member.getKind().isInterface()
                        : !isStatic && !member.getKind().isClass() && !member.getKind().isInterface();
                if (fits && isAccessible(member, declared)) {
                    addMember(member, isTypeName ? null : declared, typeElement);
                }
            }
        }

        /** No dot: locals and parameters, members of the enclosing classes, static imports, imported types. */
        void namesInScope(CompilationUnitTree unit) {
            Set<TypeElement> classesSeen = new HashSet<>();
            boolean staticOnly = isInStaticContext();
            for (Scope s = scope; s != null; s = s.getEnclosingScope()) {
                for (Element element : s.getLocalElements()) {
                    if (isLocal(element)) {
                        add(element.getSimpleName().toString(), KIND_VARIABLE, element.asType().toString(),
                                element.getSimpleName().toString(), "0");
                    }
                }
                TypeElement enclosing = s.getEnclosingClass();
                if (enclosing != null && classesSeen.add(enclosing)) {
                    for (Element member : elements.getAllMembers(enclosing)) {
                        boolean isStatic = member.getModifiers().contains(Modifier.STATIC);
                        if ((isStatic || !staticOnly) && isAccessible(member, (DeclaredType) enclosing.asType())) {
                            addMember(member, (DeclaredType) enclosing.asType(), enclosing);
                        }
                    }
                }
            }
            for (ImportTree importTree : unit.getImports()) {
                addImported(importTree);
            }
        }

        private boolean isInStaticContext() {
            ExecutableElement method = scope.getEnclosingMethod();
            return method != null && method.getModifiers().contains(Modifier.STATIC);
        }

        private static boolean isLocal(Element element) {
            return switch (element.getKind()) {
                case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER, RESOURCE_VARIABLE, BINDING_VARIABLE -> true;
                default -> false;
            };
        }

        /** {@code import static a.B.*} and {@code import static a.B.m}: B's static members; {@code import a.B}: B. */
        private void addImported(ImportTree importTree) {
            Tree qualified = importTree.getQualifiedIdentifier();
            if (!(qualified instanceof MemberSelectTree select)) {
                return;
            }
            String name = select.getIdentifier().toString();
            String owner = select.getExpression().toString();
            if (!importTree.isStatic()) {
                TypeElement type = elements.getTypeElement(owner + "." + name);
                if (type != null) {
                    addMember(type, null, type);
                }
                return;
            }
            TypeElement type = elements.getTypeElement(owner);
            if (type == null) {
                return;
            }
            for (Element member : elements.getAllMembers(type)) {
                boolean imported = name.equals("*") || member.getSimpleName().contentEquals(name);
                boolean isStatic = member.getModifiers().contains(Modifier.STATIC);
                if (imported && isStatic && isAccessible(member, (DeclaredType) type.asType())) {
                    addMember(member, null, type);
                }
            }
        }

        private boolean isAccessible(Element member, DeclaredType in) {
            try {
                return trees.isAccessible(scope, member, in);
            } catch (RuntimeException e) {
                return member.getModifiers().contains(Modifier.PUBLIC);
            }
        }

        /**
         * A method, field or type. {@code as} is the type it is a member of, for generics: a {@code State<Integer>}'s
         * {@code get()} returns {@code Integer}; null for static members. Members of {@code Object} sort last.
         */
        private void addMember(Element member, DeclaredType as, TypeElement owner) {
            String name = member.getSimpleName().toString();
            boolean inheritedFromObject = member.getEnclosingElement() != null
                    && member.getEnclosingElement().toString().equals("java.lang.Object");
            String group = inheritedFromObject ? "9" : member.getEnclosingElement() == owner ? "1" : "2";
            switch (member.getKind()) {
                case METHOD -> {
                    ExecutableElement method = (ExecutableElement) member;
                    ExecutableType signature = signatureOf(method, as);
                    StringJoiner parameters = new StringJoiner(", ", "(", ")");
                    signature.getParameterTypes().forEach(type -> parameters.add(simple(type)));
                    String detail = simple(signature.getReturnType()) + " " + name + parameters;
                    String newText = method.getParameters().isEmpty() ? name + "()" : name + "($1)";
                    add(name + parameters, KIND_METHOD, detail, newText, group, name);
                }
                case FIELD -> add(name, KIND_FIELD, simple(fieldType((VariableElement) member, as)), name, group);
                case ENUM_CONSTANT -> add(name, KIND_ENUM_MEMBER, owner.getSimpleName().toString(), name, group);
                case CLASS, RECORD, ENUM, ANNOTATION_TYPE -> add(name, KIND_CLASS, member.toString(), name, "3");
                case INTERFACE -> add(name, KIND_INTERFACE, member.toString(), name, "3");
                default -> {} // constructors, initializers
            }
        }

        private ExecutableType signatureOf(ExecutableElement method, DeclaredType as) {
            if (as != null) {
                try {
                    return (ExecutableType) types.asMemberOf(as, method);
                } catch (IllegalArgumentException e) {
                    // not a member of that type after all (a static import): its declared signature
                }
            }
            return (ExecutableType) method.asType();
        }

        private TypeMirror fieldType(VariableElement field, DeclaredType as) {
            if (as != null) {
                try {
                    return types.asMemberOf(as, field);
                } catch (IllegalArgumentException e) {
                    // see signatureOf
                }
            }
            return field.asType();
        }

        /** A type as people write it: {@code List<String>} rather than {@code java.util.List<java.lang.String>}. */
        private static String simple(TypeMirror type) {
            return type.toString().replaceAll("\\b(?:[a-z][\\w]*\\.)+([A-Z])", "$1");
        }

        private void add(String label, int kind, String detail, String newText, String group) {
            add(label, kind, detail, newText, group, label);
        }

        private void add(String label, int kind, String detail, String newText, String group, String name) {
            boolean madeUp = name.contains("$") || name.equals(MARKER);
            if (madeUp || items.containsKey(label)) {
                return; // a name JASX or javac made up, or one already offered (a local hides a field)
            }
            boolean snippet = newText.contains("$1");
            items.put(label, new Completions.Item(label, kind, detail, null, newText, snippet, group + name));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The name being typed
    // ---------------------------------------------------------------------------------------------

    /** Where the Java identifier that ends at {@code offset} starts. */
    static int identifierStart(String text, int offset) {
        int start = offset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    /** Where the Java identifier that the cursor at {@code offset} is in ends. */
    private static int identifierEnd(String text, int offset) {
        int end = offset;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
            end++;
        }
        return end;
    }
}
