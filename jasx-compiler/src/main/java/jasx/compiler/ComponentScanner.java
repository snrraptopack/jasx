package jasx.compiler;

import java.util.ArrayList;
import java.util.List;
import jasx.compiler.ComponentIndex.Children;
import jasx.compiler.ComponentIndex.Component;
import jasx.compiler.ComponentIndex.Prop;
import jasx.compiler.ComponentIndex.PropKind;
import jasx.parser.Diagnostic;
import jasx.parser.JavaLexer;

/**
 * Finds the components declared in one source file and describes their parameters (semantics.md §5.1,
 * §5.2), using the method headers that {@link JavaStructure} found.
 *
 * <p>A method is a component if it is static, returns {@code Element}, and its name doesn't start with a
 * lowercase letter (so that a tag can name it). In addition:
 *
 * <ul>
 *   <li>in a {@code .jasx} file, every such method is a component;
 *   <li>in a {@code .java} file, only such methods annotated {@code @jasx.Component} are.
 * </ul>
 *
 * <p>Each parameter becomes a prop: required by default, {@code @jasx.Optional}, {@code @jasx.Default}, or
 * the special {@code children} parameter.
 */
final class ComponentScanner {

    /** A component found in the file, with the source positions the compiler needs. */
    record FoundComponent(
            Component component,
            /** Where the method header starts: {@code @jasx.Component} is inserted here. */
            int headerStart,
            /** The method name's range, for diagnostics. */
            int nameStart,
            int nameEnd,
            /** True for a component of a {@code .jasx} file that isn't annotated yet. */
            boolean needsAnnotation) {}

    record Result(List<FoundComponent> components, List<Diagnostic> errors) {}

    private final SourceFile file;
    private final JavaStructure structure;
    private final List<FoundComponent> components = new ArrayList<>();
    private final List<Diagnostic> errors = new ArrayList<>();

    private ComponentScanner(SourceFile file, JavaStructure structure) {
        this.file = file;
        this.structure = structure;
    }

    static Result scan(SourceFile file, JavaStructure structure) {
        ComponentScanner scanner = new ComponentScanner(file, structure);
        for (JavaStructure.Method method : structure.methods) {
            scanner.examine(method);
        }
        return new Result(List.copyOf(scanner.components), List.copyOf(scanner.errors));
    }

    /** Decides whether {@code method} is a component, and if so records it. */
    private void examine(JavaStructure.Method method) {
        JavaStructure.Annotation marker = findJasxAnnotation(method.annotations(), "Component");
        boolean hasComponentShape = hasComponentShape(method);

        // An explicit @jasx.Component on a method that can't be a component is a mistake worth reporting.
        if (marker != null && !hasComponentShape) {
            errors.add(Diagnostic.error("JASX2010",
                    "@jasx.Component requires a static method that returns Element"
                            + " and whose name does not start with a lowercase letter",
                    marker.start(), marker.end()));
            return;
        }

        if (!hasComponentShape) {
            return; // An ordinary method, such as a lowercase helper that returns Element.
        }
        if (!file.isJasx() && marker == null) {
            return; // In .java files, only annotated methods are components.
        }

        List<Prop> props = new ArrayList<>();
        Children children = Children.NONE;
        for (JavaStructure.Param param : method.params()) {
            Prop prop = toProp(param);
            props.add(prop);
            if (prop.kind() == PropKind.CHILDREN) {
                children = isElementType(param.type()) ? Children.SINGLE : Children.LIST;
            }
        }

        Component component = new Component(method.owner().qualifiedName, method.name(), List.copyOf(props), children);
        boolean needsAnnotation = file.isJasx() && marker == null;
        components.add(new FoundComponent(
                component, method.headerStart(), method.nameStart(), method.nameEnd(), needsAnnotation));
    }

    /** semantics.md §5.1: static, returns Element, and the name doesn't start with a lowercase letter. */
    private static boolean hasComponentShape(JavaStructure.Method method) {
        boolean isStatic = method.modifiers().contains("static");
        boolean returnsElement = isElementType(method.returnType());
        boolean nameCanBeATag = !Character.isLowerCase(method.name().charAt(0));
        return isStatic && returnsElement && nameCanBeATag;
    }

    /** Classifies one parameter as a prop (semantics.md §5.2). */
    private Prop toProp(JavaStructure.Param param) {
        String name = param.name();
        String type = param.type();

        // The children parameter receives the element's child content instead of an attribute.
        boolean isChildren = name.equals("children") && (isElementType(type) || isElementListType(type));
        if (isChildren) {
            return new Prop(name, type, PropKind.CHILDREN, null);
        }

        JavaStructure.Annotation defaultAnnotation = findJasxAnnotation(param.annotations(), "Default");
        if (defaultAnnotation != null) {
            String literal = defaultLiteral(defaultAnnotation);
            if (literal == null) {
                errors.add(Diagnostic.error("JASX2011",
                        "the value of @jasx.Default must be the source text of a single Java literal,"
                                + " such as \"5\" or \"\\\"text\\\"\"",
                        defaultAnnotation.start(), defaultAnnotation.end()));
                // Treat it as optional, so that the bad default doesn't also cause "missing prop" errors.
                return new Prop(name, type, PropKind.OPTIONAL, null);
            }
            return new Prop(name, type, PropKind.DEFAULT, literal);
        }

        if (findJasxAnnotation(param.annotations(), "Optional") != null) {
            return new Prop(name, type, PropKind.OPTIONAL, null);
        }
        return new Prop(name, type, PropKind.REQUIRED, null);
    }

    /**
     * The literal of {@code @Default("literal")} or {@code @Default(value = "literal")}, or null if the
     * annotation's value is not a string containing exactly one Java literal.
     */
    private static String defaultLiteral(JavaStructure.Annotation annotation) {
        List<JavaLexer.Token> args = annotation.arguments();

        JavaLexer.Token value;
        if (args.size() == 1) {
            value = args.get(0); // @Default("...")
        } else if (args.size() == 3 && args.get(0).is("value") && args.get(1).is("=")) {
            value = args.get(2); // @Default(value = "...")
        } else {
            return null;
        }

        if (value.kind() != JavaLexer.Kind.STRING) {
            return null;
        }
        String literal = JavaLiterals.decodeStringLiteral(value.text());
        if (literal == null || !JavaLiterals.isSingleLiteral(literal)) {
            return null;
        }
        return literal.strip();
    }

    /**
     * Finds the annotation {@code jasx.<simpleName>} in {@code annotations}. It may be written fully
     * qualified ({@code @jasx.Optional}), or by its simple name if the file imports it ({@code @Optional}
     * with {@code import jasx.Optional;} or {@code import jasx.*;}).
     */
    private JavaStructure.Annotation findJasxAnnotation(List<JavaStructure.Annotation> annotations, String simpleName) {
        for (JavaStructure.Annotation annotation : annotations) {
            boolean qualified = annotation.name().equals("jasx." + simpleName);
            boolean importedSimpleName = annotation.name().equals(simpleName) && importsJasxType(simpleName);
            if (qualified || importedSimpleName) {
                return annotation;
            }
        }
        return null;
    }

    /** Whether the file imports {@code jasx.<simpleName>}, by name or with {@code import jasx.*;}. */
    private boolean importsJasxType(String simpleName) {
        for (JavaStructure.Import imp : structure.imports) {
            if (imp.isStatic()) {
                continue;
            }
            boolean singleType = !imp.onDemand() && imp.name().equals("jasx." + simpleName);
            boolean onDemand = imp.onDemand() && imp.name().equals("jasx");
            if (singleType || onDemand) {
                return true;
            }
        }
        return false;
    }

    /** {@code Element} or {@code jasx.runtime.Element}. Types are compared as written, without resolution. */
    private static boolean isElementType(String type) {
        return type.equals("Element") || type.equals("jasx.runtime.Element");
    }

    /** {@code List<Element>}, with either name written fully qualified or not. */
    private static boolean isElementListType(String type) {
        return type.equals("List<Element>")
                || type.equals("java.util.List<Element>")
                || type.equals("List<jasx.runtime.Element>")
                || type.equals("java.util.List<jasx.runtime.Element>");
    }
}
