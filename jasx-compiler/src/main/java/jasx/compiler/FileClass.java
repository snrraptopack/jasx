package jasx.compiler;

import java.util.ArrayList;
import java.util.List;
import javax.lang.model.SourceVersion;
import jasx.compiler.JavaStructure.TopLevelDecl;
import jasx.compiler.JavaStructure.TopLevelKind;
import jasx.parser.Diagnostic;

/**
 * File classes (semantics.md §9): a {@code .jasx} file with fields or methods at its top level is wrapped
 * in a class named after the file.
 *
 * <p>{@link JavaStructure} finds the top-level declarations. This class checks them (JASX2012, JASX2013)
 * and computes the text the lowering inserts to turn the file into its class:
 *
 * <pre>
 *   package app;                              package app;
 *   import jasx.runtime.Element;              import jasx.runtime.Element;public final class Ui { private Ui() {}
 *
 *   public Element Card() { ... }       →     public static Element Card() { ... }
 *   record Item(String name) {}               static record Item(String name) {}
 *                                             }
 * </pre>
 *
 * Nothing inserted contains a line break, except after the end of the file, so every line of the source
 * keeps its line number (semantics.md §6.5).
 */
final class FileClass {
    private FileClass() {}

    /** The problems that stop the file from being turned into its file class. */
    static List<Diagnostic> check(SourceFile file, JavaStructure structure) {
        List<Diagnostic> errors = new ArrayList<>();

        // semantics.md §9.2: the file name is the class name, so it must be a valid one.
        String name = file.baseName();
        boolean validName = SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name);
        if (structure.fileClass != null && !validName) {
            TopLevelDecl first = firstFieldOrMethod(structure);
            errors.add(Diagnostic.error("JASX2012",
                    "this file declares fields or methods at the top level, so it needs a file class named after"
                            + " the file, but '" + name + "' is not a valid Java class name",
                    first.start(), first.end()));
        }

        // semantics.md §9.2: a file class has only static members, so constructors and initializers make no
        // sense. (They are errors even in a file without a file class: Java doesn't allow them there either.)
        for (TopLevelDecl declaration : structure.topLevel) {
            boolean notAllowed = declaration.kind() == TopLevelKind.CONSTRUCTOR
                    || declaration.kind() == TopLevelKind.INITIALIZER;
            if (notAllowed) {
                errors.add(Diagnostic.error("JASX2013",
                        "a constructor or an initializer block can't be declared at the top level of a file",
                        declaration.start(), declaration.end()));
            }
        }
        return errors;
    }

    /** The insertions that wrap the file in its file class, or none if it has no file class. */
    static List<Lowering.Insertion> insertions(SourceFile file, JavaStructure structure) {
        List<Lowering.Insertion> insertions = new ArrayList<>();
        if (structure.fileClass == null) {
            return insertions;
        }
        String name = structure.fileClass.name;

        // 1. The class header, after the package and imports.
        insertions.add(new Lowering.Insertion(structure.afterImports, Lowering.Insertion.CLASS_HEADER,
                "public final class " + name + " { private " + name + "() {} "));

        // 2. 'static' in every top-level declaration that doesn't have it.
        for (TopLevelDecl declaration : structure.topLevel) {
            if (!declaration.isStatic()) {
                insertions.add(new Lowering.Insertion(declaration.staticAt(), Lowering.Insertion.MODIFIER, "static "));
            }
        }

        // 3. The closing brace, on a line of its own after the end of the file.
        String content = file.content();
        boolean endsWithLineBreak = content.endsWith("\n") || content.endsWith("\r");
        String closing = (endsWithLineBreak ? "" : "\n") + "}\n";
        insertions.add(new Lowering.Insertion(content.length(), Lowering.Insertion.CLASS_END, closing));
        return insertions;
    }

    private static TopLevelDecl firstFieldOrMethod(JavaStructure structure) {
        for (TopLevelDecl declaration : structure.topLevel) {
            if (declaration.kind() == TopLevelKind.FIELD || declaration.kind() == TopLevelKind.METHOD) {
                return declaration;
            }
        }
        return structure.topLevel.get(0);
    }
}
