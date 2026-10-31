package jasx.compiler;

import java.util.ArrayList;
import java.util.List;
import jasx.parser.JasxParser;
import jasx.parser.ParseResult;
import jasx.parser.ast.Ast.ElementName;
import jasx.parser.ast.Ast.JasxIdentifier;
import jasx.parser.ast.Ast.JasxQualifiedName;

/**
 * The components a file can use at a given place, each with the shortest tag name that refers to it there.
 * Editors use it to complete component tags.
 *
 * <p>The names are checked with the same {@link Resolver} the compiler uses (semantics.md §5.4), so a
 * suggested name always resolves to its component:
 *
 * <ol>
 *   <li>the simple name, {@code Card}, when it resolves there (a component of an enclosing class, or a
 *       statically imported one);
 *   <li>otherwise the class's simple name and the component's, {@code Ui.Card}, when that resolves (the
 *       class is in the same package, imported, or nested in an enclosing class);
 *   <li>otherwise the fully qualified name, {@code app.ui.Ui.Card}.
 * </ol>
 */
public final class VisibleComponents {
    private VisibleComponents() {}

    /** A component, and the tag name to write for it. */
    public record Entry(String name, ComponentIndex.Component component) {}

    /**
     * Every component of {@code index}, named as {@code file} would refer to it at {@code offset}.
     *
     * @param index the components of the file's group and of its dependencies
     */
    public static List<Entry> at(SourceFile file, int offset, ComponentIndex index) {
        Resolver resolver = new Resolver(structureOf(file), index);
        List<Entry> entries = new ArrayList<>();
        for (ComponentIndex.Component component : index.all()) {
            entries.add(new Entry(nameFor(component, resolver, offset), component));
        }
        return entries;
    }

    /**
     * The component that the tag name {@code tagName} ({@code Card}, {@code Ui.Card}, ...) refers to at
     * {@code offset} in {@code file}, or null if it doesn't resolve.
     */
    public static ComponentIndex.Component named(String tagName, SourceFile file, int offset, ComponentIndex index) {
        Resolver resolver = new Resolver(structureOf(file), index);
        return resolver.resolve(elementName(tagName, offset), offset).component();
    }

    /** {@code a.b.C} as the parser would build it: qualified names nested from the left. */
    private static ElementName elementName(String tagName, int offset) {
        String[] parts = tagName.split("\\.");
        ElementName name = new JasxIdentifier(offset, offset, parts[0]);
        for (int i = 1; i < parts.length; i++) {
            name = new JasxQualifiedName(offset, offset, name, new JasxIdentifier(offset, offset, parts[i]));
        }
        return name;
    }

    private static String nameFor(ComponentIndex.Component component, Resolver resolver, int offset) {
        String simple = component.method();
        if (resolvesTo(resolver.resolve(new JasxIdentifier(offset, offset, simple), offset), component)) {
            return simple;
        }

        String classSimpleName = component.className().substring(component.className().lastIndexOf('.') + 1);
        JasxQualifiedName qualified = new JasxQualifiedName(offset, offset,
                new JasxIdentifier(offset, offset, classSimpleName), new JasxIdentifier(offset, offset, simple));
        if (resolvesTo(resolver.resolve(qualified, offset), component)) {
            return classSimpleName + "." + simple;
        }

        return component.className() + "." + simple;
    }

    private static boolean resolvesTo(Resolver.Resolution resolution, ComponentIndex.Component component) {
        return resolution.component() != null
                && resolution.component().className().equals(component.className())
                && resolution.component().method().equals(component.method());
    }

    /**
     * The file's declarations and imports. A {@code .jasx} file is parsed first, so that braces in its markup
     * don't confuse the scan; the text may be half-typed, and the parser recovers from that.
     */
    private static JavaStructure structureOf(SourceFile file) {
        if (!file.isJasx()) {
            return JavaStructure.scan(file.content(), List.of(), null);
        }
        ParseResult parsed = JasxParser.parse(file.content(), file.path());
        return JavaStructure.scan(file.content(), JasxCompiler.opaqueRanges(parsed), file.baseName());
    }
}
