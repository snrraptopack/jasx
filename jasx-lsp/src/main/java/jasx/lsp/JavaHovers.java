package jasx.lsp;

import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.TreePath;
import jasx.compiler.ComponentIndex;
import jasx.compiler.GeneratedFile;
import jasx.compiler.SourceFile;
import jasx.compiler.SpanMap;
import java.util.List;
import java.util.StringJoiner;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Hover on a name in the Java code of a {@code .jasx} file, also inside {@code {...}} in markup: a local or
 * parameter shows its type ({@code State<Integer> count}, the {@code MouseEvent e} of a click handler), a
 * method its signature, a field or a type what it is, each with its Javadoc when javac has it (for the
 * group's own sources). A method that is a component shows the component's hover ({@link Hovers}).
 *
 * <p>javac analyses the file's generated Java ({@link JavacAnalysis}); the names in it ({@link JavaNames}) are
 * mapped back to the {@code .jasx} file through the span map. Only names in Java code copied from the file
 * map exactly, so code JASX wrote for markup is never matched.
 */
final class JavaHovers {
    private JavaHovers() {}

    /**
     * The Markdown for the name at {@code offset} in the file {@code path}, or null if there is no Java name
     * there, or the file has no generated Java (it has JASX errors).
     *
     * @param generated  the group's generated files
     * @param java       the group's {@code .java} files
     * @param components the components the group can use, to show a component's hover for a call
     */
    static String at(List<GeneratedFile> generated, List<SourceFile> java, String path, int offset,
            String classpath, Integer release, ComponentIndex components) {
        JavacAnalysis analysis = JavacAnalysis.of(generated, java, path, classpath, release);
        if (analysis == null) {
            return null;
        }
        try {
            JavaNames.Name name = nameAt(analysis, offset);
            if (name == null) {
                return null;
            }
            Element element = analysis.trees.getElement(name.path());
            return element == null ? null : describe(analysis, name.path(), element, components);
        } catch (RuntimeException e) {
            return null; // javac's model of broken code can surprise: no hover rather than an error
        }
    }

    /** The name whose text in the {@code .jasx} file contains {@code offset} (or ends at it). */
    private static JavaNames.Name nameAt(JavacAnalysis analysis, int offset) {
        SpanMap spans = analysis.generated.spanMap();
        String text = analysis.generated.content();
        for (JavaNames.Name name : JavaNames.in(analysis.unit, text, analysis.trees)) {
            boolean copiedFromSource = spans.isVerbatim(name.start()) && spans.isVerbatim(name.end() - 1);
            if (!copiedFromSource || name.name().contains("$")) {
                continue;
            }
            int sourceStart = spans.toSource(name.start());
            if (sourceStart <= offset && offset <= sourceStart + name.name().length()) {
                return name;
            }
        }
        return null;
    }

    private static String describe(JavacAnalysis analysis, TreePath path, Element element, ComponentIndex components) {
        if (element instanceof ExecutableElement method && componentOf(method, components) != null) {
            return Hovers.component(componentOf(method, components)); // a component called from Java
        }
        Types types = analysis.task.getTypes();
        String signature = switch (element.getKind()) {
            case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER, RESOURCE_VARIABLE, BINDING_VARIABLE ->
                    JavaCompletions.simple(element.asType()) + " " + element.getSimpleName();
            case FIELD, ENUM_CONSTANT -> field((VariableElement) element, receiverOf(analysis, path), types);
            case METHOD -> {
                ExecutableElement method = (ExecutableElement) element;
                String owner = method.getEnclosingElement().getSimpleName().toString();
                ExecutableType signatureType = memberSignature(method, receiverOf(analysis, path), types);
                yield JavaCompletions.simple(signatureType.getReturnType()) + " " + owner + "."
                        + method.getSimpleName() + parameters(method, signatureType);
            }
            case CONSTRUCTOR -> {
                ExecutableElement constructor = (ExecutableElement) element;
                yield "new " + constructor.getEnclosingElement().getSimpleName()
                        + parameters(constructor, (ExecutableType) constructor.asType());
            }
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE ->
                    kindWord(element) + " " + ((TypeElement) element).getQualifiedName();
            case TYPE_PARAMETER -> "type parameter " + element.getSimpleName();
            case PACKAGE -> "package " + element;
            default -> null;
        };
        if (signature == null) {
            return null;
        }
        StringBuilder markdown = new StringBuilder("```java\n").append(signature).append("\n```");
        String doc = analysis.task.getElements().getDocComment(element);
        if (doc != null && !doc.isBlank()) {
            markdown.append("\n\n").append(doc.strip().replaceAll("\n\\s*", "\n"));
        }
        return markdown.toString();
    }

    /** For {@code a.b}: the type of {@code a}, so that a generic member shows its actual types. */
    private static DeclaredType receiverOf(JavacAnalysis analysis, TreePath path) {
        if (path.getLeaf() instanceof MemberSelectTree select) {
            TypeMirror type = analysis.trees.getTypeMirror(new TreePath(path, select.getExpression()));
            if (type instanceof DeclaredType declared) {
                return declared;
            }
        }
        return null;
    }

    /** A method's signature as a member of the receiver's type: {@code State<Integer>.get()} returns Integer. */
    private static ExecutableType memberSignature(ExecutableElement method, DeclaredType receiver, Types types) {
        if (receiver != null) {
            try {
                return (ExecutableType) types.asMemberOf(receiver, method);
            } catch (IllegalArgumentException e) {
                // not a member of that type after all (a static method): its declared signature
            }
        }
        return (ExecutableType) method.asType();
    }

    /** {@code (String title, List<Element> children)}: the parameter types with their names. */
    private static String parameters(ExecutableElement method, ExecutableType signature) {
        StringJoiner parameters = new StringJoiner(", ", "(", ")");
        for (int i = 0; i < method.getParameters().size(); i++) {
            parameters.add(JavaCompletions.simple(signature.getParameterTypes().get(i)) + " "
                    + method.getParameters().get(i).getSimpleName());
        }
        return parameters.toString();
    }

    private static String field(VariableElement field, DeclaredType receiver, Types types) {
        TypeMirror type = field.asType();
        if (receiver != null) {
            try {
                type = types.asMemberOf(receiver, field);
            } catch (IllegalArgumentException e) {
                // a static field: its declared type
            }
        }
        return JavaCompletions.simple(type) + " " + field.getEnclosingElement().getSimpleName() + "." + field.getSimpleName();
    }

    /** The component a method is, if the group knows it as one. */
    private static ComponentIndex.Component componentOf(ExecutableElement method, ComponentIndex components) {
        if (!(method.getEnclosingElement() instanceof TypeElement owner)) {
            return null;
        }
        return components.get(owner.getQualifiedName().toString(), method.getSimpleName().toString());
    }

    private static String kindWord(Element type) {
        return switch (type.getKind()) {
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record";
            case ANNOTATION_TYPE -> "@interface";
            default -> "class";
        };
    }
}
