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

/** Resolves component element names against the component index (semantics.md §5.4). */
final class Resolver {
    /** Exactly one of {@code component} and {@code error} is non-null. */
    record Resolution(ComponentIndex.Component component, Diagnostic error) {}

    private final JavaStructure structure;
    private final ComponentIndex index;

    Resolver(JavaStructure structure, ComponentIndex index) {
        this.structure = structure;
        this.index = index;
    }

    /** Resolves {@code name}, written at {@code offset} in the file. */
    Resolution resolve(ElementName name, int offset) {
        return switch (name) {
            case JasxIdentifier id -> simple(id, offset);
            case JasxQualifiedName q -> qualified(q, offset);
        };
    }

    private Resolution simple(JasxIdentifier id, int offset) {
        String n = id.name();
        for (JavaStructure.TypeDecl t = structure.enclosingType(offset); t != null; t = t.parent) {
            ComponentIndex.Component c = index.get(t.qualifiedName, n);
            if (c != null) {
                return found(c);
            }
        }
        for (JavaStructure.Import imp : structure.imports) {
            if (imp.isStatic() && !imp.onDemand() && imp.name().endsWith("." + n)) {
                ComponentIndex.Component c = index.get(imp.name().substring(0, imp.name().length() - n.length() - 1), n);
                if (c != null) {
                    return found(c);
                }
            }
        }
        List<ComponentIndex.Component> matches = new ArrayList<>();
        for (JavaStructure.Import imp : structure.imports) {
            if (imp.isStatic() && imp.onDemand()) {
                ComponentIndex.Component c = index.get(imp.name(), n);
                if (c != null) {
                    matches.add(c);
                }
            }
        }
        if (matches.size() == 1) {
            return found(matches.get(0));
        }
        if (matches.size() > 1) {
            String classes = String.join(" and ", matches.stream().map(ComponentIndex.Component::className).toList());
            return error("JASX2002", "component <" + n + "> is ambiguous: it is imported from " + classes, id);
        }
        return error("JASX2001", "cannot resolve component <" + n + ">", id);
    }

    private Resolution qualified(JasxQualifiedName q, int offset) {
        String qualifier = JasxParser.nameText(q.qualifier());
        String n = q.name().name();
        String first = qualifier.contains(".") ? qualifier.substring(0, qualifier.indexOf('.')) : qualifier;
        String rest = qualifier.substring(first.length());
        Set<String> candidates = new LinkedHashSet<>();
        for (JavaStructure.TypeDecl t = structure.enclosingType(offset); t != null; t = t.parent) {
            candidates.add(t.qualifiedName + "." + qualifier);
        }
        for (JavaStructure.Import imp : structure.imports) {
            if (!imp.isStatic() && !imp.onDemand() && imp.name().endsWith("." + first)) {
                candidates.add(imp.name() + rest);
            }
        }
        candidates.add(structure.packageName.isEmpty() ? qualifier : structure.packageName + "." + qualifier);
        for (JavaStructure.Import imp : structure.imports) {
            if (!imp.isStatic() && imp.onDemand()) {
                candidates.add(imp.name() + "." + qualifier);
            }
        }
        candidates.add(qualifier);
        for (String className : candidates) {
            ComponentIndex.Component c = index.get(className, n);
            if (c != null) {
                return found(c);
            }
        }
        return error("JASX2001", "cannot resolve component <" + qualifier + "." + n + ">", q);
    }

    private static Resolution found(ComponentIndex.Component c) {
        return new Resolution(c, null);
    }

    private static Resolution error(String code, String message, ElementName at) {
        return new Resolution(null, Diagnostic.error(code, message, at.start(), at.end()));
    }
}
