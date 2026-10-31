package jasx.processor;

import jasx.compiler.ComponentIndex;
import jasx.compiler.ComponentIndex.Children;
import jasx.compiler.ComponentIndex.Prop;
import jasx.compiler.ComponentIndex.PropKind;
import jasx.compiler.JavaLiterals;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Writes the component index {@code META-INF/jasx/components.json} for the classes being compiled
 * (semantics.md §5.3). It indexes every method annotated {@code @jasx.Component}, which includes the
 * components of {@code .jasx} files, because the JASX compiler adds the annotation to them.
 *
 * <p>Annotations are read through mirrors, so the processor doesn't need {@code jasx-runtime} on its path.
 */
@SupportedAnnotationTypes("jasx.Component")
public final class ComponentProcessor extends AbstractProcessor {
    private static final String ELEMENT = "jasx.runtime.Element";

    private final ComponentIndex index = new ComponentIndex();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        TypeElement marker = processingEnv.getElementUtils().getTypeElement("jasx.Component");
        if (marker != null) {
            for (Element e : round.getElementsAnnotatedWith(marker)) {
                if (e.getKind() == ElementKind.METHOD) {
                    record((ExecutableElement) e);
                }
            }
        }
        if (round.processingOver() && !index.all().isEmpty()) {
            write();
        }
        return false;
    }

    private void record(ExecutableElement method) {
        String name = method.getSimpleName().toString();
        boolean valid = method.getModifiers().contains(Modifier.STATIC)
                && method.getReturnType().toString().equals(ELEMENT)
                && !Character.isLowerCase(name.charAt(0));
        if (!valid) {
            error("@jasx.Component requires a static method that returns Element and whose name does not start"
                    + " with a lowercase letter [JASX2010]", method);
            return;
        }
        if (!(method.getEnclosingElement() instanceof TypeElement owner) || owner.getQualifiedName().isEmpty()) {
            error("a component must be declared in a named class [JASX2010]", method);
            return;
        }
        List<Prop> params = new ArrayList<>();
        Children children = Children.NONE;
        for (VariableElement p : method.getParameters()) {
            String paramName = p.getSimpleName().toString();
            String type = p.asType().toString();
            if (paramName.equals("children") && type.equals(ELEMENT)) {
                children = Children.SINGLE;
                params.add(new Prop(paramName, type, PropKind.CHILDREN, null));
            } else if (paramName.equals("children") && type.equals("java.util.List<" + ELEMENT + ">")) {
                children = Children.LIST;
                params.add(new Prop(paramName, type, PropKind.CHILDREN, null));
            } else if (annotation(p, "jasx.Default") != null) {
                String literal = stringValue(annotation(p, "jasx.Default"));
                if (literal == null || !JavaLiterals.isSingleLiteral(literal)) {
                    error("the value of @jasx.Default must be the source text of a single Java literal [JASX2011]", p);
                    params.add(new Prop(paramName, type, PropKind.OPTIONAL, null));
                } else {
                    params.add(new Prop(paramName, type, PropKind.DEFAULT, literal.strip()));
                }
            } else if (annotation(p, "jasx.Optional") != null) {
                params.add(new Prop(paramName, type, PropKind.OPTIONAL, null));
            } else {
                params.add(new Prop(paramName, type, PropKind.REQUIRED, null));
            }
        }
        String className = owner.getQualifiedName().toString();
        if (!index.add(new ComponentIndex.Component(className, name, List.copyOf(params), children))) {
            error("component " + name + " is already declared in " + className
                    + "; components cannot be overloaded [JASX2006]", method);
        }
    }

    private void write() {
        try {
            FileObject file = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", ComponentIndex.RESOURCE);
            try (Writer out = file.openWriter()) {
                out.write(index.toJson());
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "cannot write " + ComponentIndex.RESOURCE + ": " + e.getMessage());
        }
    }

    private static AnnotationMirror annotation(Element e, String type) {
        for (AnnotationMirror m : e.getAnnotationMirrors()) {
            if (m.getAnnotationType().toString().equals(type)) {
                return m;
            }
        }
        return null;
    }

    private static String stringValue(AnnotationMirror m) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : m.getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals("value") && entry.getValue().getValue() instanceof String s) {
                return s;
            }
        }
        return null;
    }

    private void error(String message, Element at) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, at);
    }
}
