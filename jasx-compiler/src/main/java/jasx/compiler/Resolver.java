package jasx.compiler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import jasx.parser.Diagnostic;
import jasx.parser.JasxParser;
import jasx.parser.ast.Ast.ElementName;
import jasx.parser.ast.Ast.JasxIdentifier;
import jasx.parser.ast.Ast.JasxQualifiedName;

/**
 * Finds the component that a component tag refers to (semantics.md §5.4).
 *
 * <p>A <b>simple name</b> {@code <Card>} is looked up, in order:
 *
 * <ol>
 *   <li>in the class that contains the markup, then in each enclosing class;
 *   <li>through a single static import, {@code import static app.Ui.Card;};
 *   <li>through static on-demand imports, {@code import static app.Ui.*;}. If two of them provide the
 *       name, it is ambiguous (JASX2002).
 * </ol>
 *
 * <p>A <b>qualified name</b> {@code <Ui.Card>} means "the component {@code Card} of the class {@code Ui}".
 * {@code Ui} is resolved the way Java resolves a type name, from the most local to the least: a nested
 * class of an enclosing class, a single-type import, the current package, an on-demand import, and
 * finally a fully qualified name.
 *
 * <p>The resolver only uses the component index and the file's imports. It never needs Java types.
 */
final class Resolver {
    /** The result of resolving a name: exactly one of {@code component} and {@code error} is non-null. */
    record Resolution(ComponentIndex.Component component, Diagnostic error) {}

    private final JavaStructure structure;
    private final ComponentIndex index;

    Resolver(JavaStructure structure, ComponentIndex index) {
        this.structure = structure;
        this.index = index;
    }

    /** Resolves {@code name}, which appears at {@code offset} in the file. */
    Resolution resolve(ElementName name, int offset) {
        return switch (name) {
            case JasxIdentifier simple -> resolveSimple(simple, offset);
            case JasxQualifiedName qualified -> resolveQualified(qualified, offset);
        };
    }

    // ---------------------------------------------------------------------------------------------
    // <Card>
    // ---------------------------------------------------------------------------------------------

    private Resolution resolveSimple(JasxIdentifier id, int offset) {
        String name = id.name();

        // 1. The enclosing classes, innermost first.
        for (JavaStructure.TypeDecl type = structure.enclosingType(offset); type != null; type = type.parent) {
            ComponentIndex.Component component = index.get(type.qualifiedName, name);
            if (component != null) {
                return found(component);
            }
        }

        // 2. import static app.Ui.Card;
        for (JavaStructure.Import imp : structure.imports) {
            boolean importsThisName = imp.isStatic() && !imp.onDemand() && imp.name().endsWith("." + name);
            if (importsThisName) {
                String className = imp.name().substring(0, imp.name().length() - name.length() - 1);
                ComponentIndex.Component component = index.get(className, name);
                if (component != null) {
                    return found(component);
                }
            }
        }

        // 3. import static app.Ui.*;  (several matches are ambiguous)
        List<ComponentIndex.Component> matches = new ArrayList<>();
        for (JavaStructure.Import imp : structure.imports) {
            if (imp.isStatic() && imp.onDemand()) {
                ComponentIndex.Component component = index.get(imp.name(), name);
                if (component != null) {
                    matches.add(component);
                }
            }
        }
        if (matches.size() == 1) {
            return found(matches.get(0));
        }
        if (matches.size() > 1) {
            List<String> classes = matches.stream().map(ComponentIndex.Component::className).toList();
            String message = "component <" + name + "> is ambiguous: it is imported from " + String.join(" and ", classes);
            return error("JASX2002", message, id);
        }

        return error("JASX2001", "cannot resolve component <" + name + ">", id);
    }

    // ---------------------------------------------------------------------------------------------
    // <Ui.Card>
    // ---------------------------------------------------------------------------------------------

    private Resolution resolveQualified(JasxQualifiedName qualifiedName, int offset) {
        String typeName = JasxParser.nameText(qualifiedName.qualifier()); // "Ui" or "app.ui.Ui"
        String componentName = qualifiedName.name().name(); // "Card"

        for (String className : candidateClassNames(typeName, offset)) {
            ComponentIndex.Component component = index.get(className, componentName);
            if (component != null) {
                return found(component);
            }
        }
        return error("JASX2001", "cannot resolve component <" + typeName + "." + componentName + ">", qualifiedName);
    }

    /**
     * The fully qualified class names that the type name {@code typeName} could mean in this file, most
     * local first. For {@code Ui} in package {@code app}, with {@code import lib.*;}: {@code app.Page.Ui}
     * (a nested class), {@code app.Ui}, {@code lib.Ui}, and {@code Ui}.
     */
    private Set<String> candidateClassNames(String typeName, int offset) {
        Set<String> candidates = new LinkedHashSet<>();

        // A nested class of the current class or of an enclosing class.
        for (JavaStructure.TypeDecl type = structure.enclosingType(offset); type != null; type = type.parent) {
            candidates.add(type.qualifiedName + "." + typeName);
        }

        // A single-type import of the first segment: "import app.ui.Ui;" makes "Ui.Inner" mean "app.ui.Ui.Inner".
        int dot = typeName.indexOf('.');
        String firstSegment = dot < 0 ? typeName : typeName.substring(0, dot);
        String rest = typeName.substring(firstSegment.length());
        for (JavaStructure.Import imp : structure.imports) {
            boolean importsFirstSegment = !imp.isStatic() && !imp.onDemand() && imp.name().endsWith("." + firstSegment);
            if (importsFirstSegment) {
                candidates.add(imp.name() + rest);
            }
        }

        // The current package.
        candidates.add(structure.packageName.isEmpty() ? typeName : structure.packageName + "." + typeName);

        // On-demand imports: "import lib.*;"
        for (JavaStructure.Import imp : structure.imports) {
            if (!imp.isStatic() && imp.onDemand()) {
                candidates.add(imp.name() + "." + typeName);
            }
        }

        // A fully qualified name: <app.ui.Ui.Card>
        candidates.add(typeName);
        return candidates;
    }

    private static Resolution found(ComponentIndex.Component component) {
        return new Resolution(component, null);
    }

    private static Resolution error(String code, String message, ElementName at) {
        return new Resolution(null, Diagnostic.error(code, message, at.start(), at.end()));
    }
}
