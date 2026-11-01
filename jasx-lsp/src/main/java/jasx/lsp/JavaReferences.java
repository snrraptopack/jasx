package jasx.lsp;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import jasx.compiler.CompilationResult;
import jasx.compiler.ComponentIndex;
import jasx.compiler.SpanMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Find references and rename: the symbol at a position of a {@code .jasx} file, and every place in its group
 * where it is written.
 *
 * <p>A symbol is anything javac names: a component, a prop, a method, a field, a local, a parameter, a type.
 * Its places are found in three ways:
 *
 * <ul>
 *   <li>in Java code, of {@code .jasx} files and of {@code .java} files: every name javac resolves to the
 *       same element ({@link JavaNames}); in a {@code .jasx} file only names in Java code copied from it count,
 *       because code JASX wrote for markup maps only to the markup as a whole;
 *   <li>a component's tags in markup ({@code CompilationResult.uses}): the last part of the tag's name
 *       ({@code Card} in {@code <Ui.Card>}), and for a class, its part of the tag ({@code Ui});
 *   <li>a prop's attributes in markup ({@code CompilationResult.props}): {@code title} in
 *       {@code <Card title="x">}.
 * </ul>
 *
 * <p>Only the group is searched: a component's uses in another source set (tests) or another module are
 * not found, and a rename leaves them for the next build to report.
 */
final class JavaReferences {
    private JavaReferences() {}

    /** One place where the symbol is written: {@code [start, end)} in the text of the file {@code path}. */
    record Occurrence(String path, int start, int end, boolean declaration) {}

    /**
     * The symbol at a position.
     *
     * @param name        its name, as written at the position
     * @param at          the place at the position
     * @param occurrences every place in the group, the declaration included, in file and text order
     * @param refusal     why it can't be renamed, or null if it can
     * @param kind        what it is, for messages about a new name
     */
    record Symbol(String name, Occurrence at, List<Occurrence> occurrences, String refusal, Kind kind) {}

    /** What a symbol is, as far as renaming it is concerned. */
    enum Kind { COMPONENT, PROP, CHILDREN, OTHER }

    /**
     * The symbol at {@code offset} in the {@code .jasx} file {@code path}, or null if there is none there.
     *
     * @param analysis javac's view of the group, centred on {@code path}
     * @param result   the group's JASX analysis (tags and props in markup)
     * @param texts    the text of each file of the group, by path
     */
    static Symbol at(JavacAnalysis analysis, CompilationResult result, Map<String, String> texts, String path,
            int offset) {
        Search search = new Search(analysis, result, texts);
        Target target = search.targetAt(path, offset);
        return target == null ? null : search.symbolOf(target);
    }

    /**
     * Why {@code newName} can't replace the symbol's name, or null if it can: it must be a Java name, a
     * component's name must not start with a lowercase letter (semantics.md §5.1), and a prop can't become,
     * or stop being, the children (§5.2).
     */
    static String refusalOf(Symbol symbol, String newName) {
        if (!SourceVersion.isName(newName)) {
            return "'" + newName + "' is not a valid Java name";
        }
        if (symbol.kind() == Kind.COMPONENT && Character.isLowerCase(newName.charAt(0))) {
            return "a component's name must not start with a lowercase letter: it would stop being a component";
        }
        if (symbol.kind() == Kind.PROP && newName.equals("children")) {
            return "a prop named 'children' takes the children between the tags; pick another name";
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    /** The element at a position, and the place there. */
    private record Target(Element element, Occurrence at) {}

    /** One search over a group. */
    private static final class Search {
        private final JavacAnalysis analysis;
        private final CompilationResult result;
        private final Map<String, String> texts;
        private final Elements elements;
        private final Types types;

        Search(JavacAnalysis analysis, CompilationResult result, Map<String, String> texts) {
            this.analysis = analysis;
            this.result = result;
            this.texts = texts;
            this.elements = analysis.task.getElements();
            this.types = analysis.task.getTypes();
        }

        // --- What is at the position ------------------------------------------------------------

        Target targetAt(String path, int offset) {
            for (CompilationResult.Use use : result.uses()) {
                if (use.path().equals(path) && use.nameStart() <= offset && offset <= use.nameEnd()) {
                    return onTag(use, offset);
                }
            }
            for (CompilationResult.PropUse prop : result.props()) {
                if (prop.path().equals(path) && prop.nameStart() <= offset && offset <= prop.nameEnd()) {
                    Element parameter = parameter(prop.component(), prop.prop());
                    Occurrence at = new Occurrence(path, prop.nameStart(), prop.nameEnd(), false);
                    return parameter == null ? null : new Target(parameter, at);
                }
            }
            for (Located name : javaNames(analysis.unit)) {
                if (name.at().start() <= offset && offset <= name.at().end()) {
                    Element element = analysis.trees.getElement(name.path());
                    return element == null ? null : new Target(element, name.at());
                }
            }
            return null;
        }

        /**
         * The target on a component's tag: the component on the last part of its name ({@code Card} in
         * {@code <Ui.Card>}), the class a qualifier part names on that part ({@code Ui}), and nothing on a
         * package name ({@code app} in {@code <app.Ui.Card>}).
         */
        private Target onTag(CompilationResult.Use use, int offset) {
            String tag = texts.get(use.path()).substring(use.nameStart(), use.nameEnd());
            String[] parts = tag.split("\\.");
            int partStart = use.nameStart();
            int index = 0;
            while (index < parts.length - 1 && offset > partStart + parts[index].length()) {
                partStart += parts[index].length() + 1;
                index++;
            }
            if (index == parts.length - 1) {
                Element method = componentMethod(use.component());
                return method == null ? null : new Target(method, lastPartOf(use));
            }
            // The class parts of the tag line up with the end of the component's class name: in <Outer.Inner.Card>
            // of app.Outer.Inner, "Inner" is app.Outer.Inner and "Outer" is app.Outer.
            String[] classParts = use.component().className().split("\\.");
            int dropped = parts.length - 2 - index;
            if (dropped >= classParts.length) {
                return null;
            }
            String className = String.join(".", java.util.Arrays.copyOf(classParts, classParts.length - dropped));
            TypeElement type = elements.getTypeElement(className);
            Occurrence at = new Occurrence(use.path(), partStart, partStart + parts[index].length(), false);
            return type == null ? null : new Target(type, at);
        }

        /** The method a component is, as javac knows it. */
        private ExecutableElement componentMethod(ComponentIndex.Component component) {
            TypeElement owner = elements.getTypeElement(component.className());
            if (owner == null) {
                return null;
            }
            for (ExecutableElement method : ElementFilter.methodsIn(owner.getEnclosedElements())) {
                if (method.getSimpleName().contentEquals(component.method())) {
                    return method; // components are never overloaded (semantics.md §5.1)
                }
            }
            return null;
        }

        private VariableElement parameter(ComponentIndex.Component component, String prop) {
            ExecutableElement method = componentMethod(component);
            if (method == null) {
                return null;
            }
            for (VariableElement parameter : method.getParameters()) {
                if (parameter.getSimpleName().contentEquals(prop)) {
                    return parameter;
                }
            }
            return null;
        }

        // --- Every place it is written ----------------------------------------------------------

        Symbol symbolOf(Target target) {
            Element element = target.element();
            Map<String, Occurrence> found = new LinkedHashMap<>(); // by path and start: no place twice
            for (CompilationUnitTree unit : analysis.group) {
                for (Located name : javaNames(unit)) {
                    Element named = analysis.trees.getElement(name.path());
                    if (named != null && names(named, element)) {
                        add(found, name.at());
                    }
                }
            }
            ComponentIndex.Component component = componentOf(element);
            if (component != null) {
                for (CompilationResult.Use use : result.uses()) {
                    if (same(use.component(), component)) {
                        add(found, lastPartOf(use));
                    }
                }
            }
            if (element.getKind() == ElementKind.PARAMETER) {
                ComponentIndex.Component owner = componentOf(element.getEnclosingElement());
                for (CompilationResult.PropUse prop : result.props()) {
                    boolean same = owner != null && same(prop.component(), owner)
                            && prop.prop().contentEquals(element.getSimpleName());
                    if (same) {
                        add(found, new Occurrence(prop.path(), prop.nameStart(), prop.nameEnd(), false));
                    }
                }
            }
            if (element instanceof TypeElement type) {
                for (CompilationResult.Use use : result.uses()) {
                    Occurrence part = typePartOf(use, type);
                    if (part != null) {
                        add(found, part);
                    }
                }
            }

            List<Occurrence> occurrences = new ArrayList<>(found.values());
            occurrences.sort(Comparator.comparing(Occurrence::path).thenComparingInt(Occurrence::start));
            String name = texts.get(target.at().path()).substring(target.at().start(), target.at().end());
            return new Symbol(name, target.at(), occurrences, refusal(element, occurrences), kindOf(element));
        }

        private static void add(Map<String, Occurrence> found, Occurrence occurrence) {
            found.merge(occurrence.path() + ":" + occurrence.start(), occurrence,
                    (first, second) -> first.declaration() ? first : second);
        }

        /** Whether a name resolving to {@code named} writes {@code element}: itself, or a constructor of a class. */
        private static boolean names(Element named, Element element) {
            boolean constructorOfClass = named.getKind() == ElementKind.CONSTRUCTOR
                    && element instanceof TypeElement && named.getEnclosingElement().equals(element);
            return named.equals(element) || constructorOfClass;
        }

        /** {@code Card} in {@code <Ui.Card>}: the part of a tag's name that names the method. */
        private Occurrence lastPartOf(CompilationResult.Use use) {
            String tag = texts.get(use.path()).substring(use.nameStart(), use.nameEnd());
            int start = use.nameStart() + tag.lastIndexOf('.') + 1;
            return new Occurrence(use.path(), start, use.nameEnd(), false);
        }

        /**
         * The part of a tag's name that names {@code type}, if the tag's component is declared in it (or in a
         * type nested in it) and the tag spells it out: {@code Ui} in {@code <Ui.Card>}, not in {@code <Card>}.
         */
        private Occurrence typePartOf(CompilationResult.Use use, TypeElement type) {
            String typeName = type.getQualifiedName().toString();
            String owner = use.component().className();
            if (!owner.equals(typeName) && !owner.startsWith(typeName + ".")) {
                return null;
            }
            int nestedBelow = owner.equals(typeName) ? 0 : owner.substring(typeName.length() + 1).split("\\.").length;
            String tag = texts.get(use.path()).substring(use.nameStart(), use.nameEnd());
            String[] parts = tag.split("\\.");
            int index = parts.length - 2 - nestedBelow; // the method is the last part
            if (index < 0 || !parts[index].contentEquals(type.getSimpleName())) {
                return null;
            }
            int start = use.nameStart();
            for (int i = 0; i < index; i++) {
                start += parts[i].length() + 1;
            }
            return new Occurrence(use.path(), start, start + parts[index].length(), false);
        }

        // --- Names in Java code -----------------------------------------------------------------

        /** A name of {@link JavaNames}, placed in its source file. */
        private record Located(TreePath path, Occurrence at) {}

        /**
         * The names of a file that are written in its source: all of a {@code .java} file's, and those of a
         * {@code .jasx} file's generated Java that were copied from it, mapped back to it.
         */
        private List<Located> javaNames(CompilationUnitTree unit) {
            JavacAnalysis.Origin origin = analysis.originOf(unit);
            if (origin == null) {
                return List.of();
            }
            String source = texts.get(origin.path());
            String parsed = origin.generated() != null ? origin.generated().content() : source;
            if (source == null) {
                return List.of();
            }
            List<Located> located = new ArrayList<>();
            for (JavaNames.Name name : JavaNames.in(unit, parsed, analysis.trees)) {
                int start = name.start();
                if (origin.generated() != null) {
                    SpanMap spans = origin.generated().spanMap();
                    boolean copied = spans.isVerbatim(name.start()) && spans.isVerbatim(name.end() - 1);
                    if (!copied) {
                        continue;
                    }
                    start = spans.toSource(name.start());
                }
                int end = start + name.name().length();
                boolean writtenThere = end <= source.length() && source.startsWith(name.name(), start);
                if (writtenThere && !name.name().contains("$")) {
                    located.add(new Located(name.path(), new Occurrence(origin.path(), start, end, name.declaration())));
                }
            }
            return located;
        }

        // --- What can be renamed ----------------------------------------------------------------

        private ComponentIndex.Component componentOf(Element element) {
            if (!(element instanceof ExecutableElement method) || !(method.getEnclosingElement() instanceof TypeElement owner)) {
                return null;
            }
            return result.index().get(owner.getQualifiedName().toString(), method.getSimpleName().toString());
        }

        private static boolean same(ComponentIndex.Component a, ComponentIndex.Component b) {
            return a.className().equals(b.className()) && a.method().equals(b.method());
        }

        private Kind kindOf(Element element) {
            if (componentOf(element) != null) {
                return Kind.COMPONENT;
            }
            boolean isProp = element.getKind() == ElementKind.PARAMETER && componentOf(element.getEnclosingElement()) != null;
            if (isProp) {
                return element.getSimpleName().contentEquals("children") ? Kind.CHILDREN : Kind.PROP;
            }
            return Kind.OTHER;
        }

        /** Why the element can't be renamed, or null. */
        private String refusal(Element element, List<Occurrence> occurrences) {
            boolean declaredHere = occurrences.stream().anyMatch(Occurrence::declaration);
            if (!declaredHere) {
                return element instanceof TypeElement
                        ? "this class isn't declared in the code of this source set (a .jasx file's class is named after the file)"
                        : "'" + element.getSimpleName() + "' isn't declared in this source set";
            }
            if (element instanceof TypeElement type && !type.getNestingKind().isNested()) {
                return "a top-level class is named after its file: rename the file instead";
            }
            if (kindOf(element) == Kind.CHILDREN) {
                return "the 'children' parameter takes the children between the tags; renaming it would make it a prop";
            }
            if (element instanceof ExecutableElement method && element.getKind() == ElementKind.METHOD) {
                return overrideRefusal(method);
            }
            return null;
        }

        /** A method that overrides another, or is overridden, can't be renamed alone. */
        private String overrideRefusal(ExecutableElement method) {
            TypeElement owner = (TypeElement) method.getEnclosingElement();
            for (TypeElement supertype : supertypesOf(owner.asType(), new ArrayList<>())) {
                for (ExecutableElement other : ElementFilter.methodsIn(supertype.getEnclosedElements())) {
                    if (elements.overrides(method, other, owner)) {
                        return "it overrides " + supertype.getSimpleName() + "." + other.getSimpleName()
                                + "; renaming it alone would break that";
                    }
                }
            }
            for (TypeElement type : typesOfGroup()) {
                for (ExecutableElement other : ElementFilter.methodsIn(type.getEnclosedElements())) {
                    if (!other.equals(method) && elements.overrides(other, method, type)) {
                        return "it is overridden in " + type.getSimpleName() + "; renaming it alone would break that";
                    }
                }
            }
            return null;
        }

        private List<TypeElement> supertypesOf(TypeMirror type, List<TypeElement> into) {
            for (TypeMirror supertype : types.directSupertypes(type)) {
                if (supertype instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
                        && !into.contains(element)) {
                    into.add(element);
                    supertypesOf(supertype, into);
                }
            }
            return into;
        }

        /** Every class declared in the group, nested ones included. */
        private List<TypeElement> typesOfGroup() {
            List<TypeElement> found = new ArrayList<>();
            for (CompilationUnitTree unit : analysis.group) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitClass(ClassTree tree, Void unused) {
                        if (analysis.trees.getElement(getCurrentPath()) instanceof TypeElement type) {
                            found.add(type);
                        }
                        return super.visitClass(tree, unused);
                    }
                }.scan(unit, null);
            }
            return found;
        }
    }
}
