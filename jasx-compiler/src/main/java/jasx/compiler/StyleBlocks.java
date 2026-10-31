package jasx.compiler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import jasx.parser.Diagnostic;
import jasx.parser.ParseResult;
import jasx.parser.ast.Ast.Child;
import jasx.parser.ast.Ast.JasxAttribute;
import jasx.parser.ast.Ast.JasxElement;
import jasx.parser.ast.Ast.JasxExpressionContainer;
import jasx.parser.ast.Ast.JasxFragment;
import jasx.parser.ast.Ast.JasxStyleBlock;
import jasx.parser.ast.Ast.JavaExpression;
import jasx.parser.ast.Ast.Node;
import jasx.parser.ast.Ast.Segment;

/**
 * Checks the style blocks of a {@code .jasx} file and prepares its style sheet (semantics.md §10).
 *
 * <p>A file may have several style blocks: in Java code (in a class body, in a method before or between
 * statements, or at the top level of a file class), and inside markup. They all go into one style sheet
 * with one scope name. This class:
 *
 * <ul>
 *   <li>finds every style block in the AST;
 *   <li>checks that the target supports them (JASX2106) and that each block in Java code is inside a class
 *       (JASX2015, using the places {@link JavaStructure} found);
 *   <li>scopes the CSS of each block with {@link ScopedCss} (JASX2014) and joins the results;
 *   <li>decides the <em>style class</em> that declares the style sheet field, and where the field goes.
 * </ul>
 */
final class StyleBlocks {
    private StyleBlocks() {}

    /** The length of {@code <style>}, where the CSS content starts. */
    private static final int OPEN_TAG_LENGTH = "<style>".length();

    /**
     * The style sheet of a file.
     *
     * @param styleClass the fully qualified class that declares the field, such as {@code app.Ui}
     * @param scope the scope name, such as {@code jx-1a2b3c4d} (semantics.md §10.3)
     * @param css the scoped CSS of the file (semantics.md §10.4)
     * @param fieldOffset where the field declaration is inserted: the start of the style class's body
     */
    record Plan(String styleClass, String scope, String css, int fieldOffset) {
        /** The name of the generated field (semantics.md §10.5). */
        static final String FIELD = "jasx$styles";

        /** How generated code refers to the style sheet: {@code app.Ui.jasx$styles}. */
        String fieldReference() {
            return styleClass + "." + FIELD;
        }

        /** The insertion that declares the field, with the target's factory. */
        Lowering.Insertion fieldDeclaration(String factory) {
            String declaration = "static final java.lang.Object " + FIELD + " = " + factory + ".styleSheet("
                    + Lowering.javaString(scope) + ", " + Lowering.javaString(css) + "); ";
            return new Lowering.Insertion(fieldOffset, Lowering.Insertion.STYLE_SHEET, declaration);
        }
    }

    /** The plan for the file's style sheet (null if it has no style block, or errors), and the problems. */
    record Result(Plan plan, List<Diagnostic> errors) {}

    static Result check(SourceFile file, ParseResult parsed, JavaStructure structure, Target target) {
        List<JasxStyleBlock> javaBlocks = new ArrayList<>();        // in Java code (segments), in source order
        List<JasxStyleBlock> allBlocks = new ArrayList<>();         // every block, in source order
        for (Segment segment : parsed.unit().segments()) {
            if (segment instanceof JasxStyleBlock block) {
                javaBlocks.add(block);
            }
            collect(segment, allBlocks);
        }
        if (allBlocks.isEmpty()) {
            return new Result(null, List.of());
        }

        List<Diagnostic> errors = new ArrayList<>();
        JasxStyleBlock first = allBlocks.get(0);

        // semantics.md §10.6: the target must support style blocks.
        if (!target.styles()) {
            errors.add(error(first, "JASX2106", "target '" + target.id() + "' does not support style blocks"));
        }

        // semantics.md §10.1: a block outside markup must be inside a class: in a class body or a method, or
        // at the top level of a file class. The structure found those blocks, in the same order.
        for (int i = 0; i < javaBlocks.size(); i++) {
            if (structure.styleBlocks.get(i).owner() == null) {
                errors.add(error(javaBlocks.get(i), "JASX2015", "a style block must be inside a class;"
                        + " at the top level, it needs a file class, so the file must declare a field or method"
                        + " at the top level too"));
            }
        }

        // semantics.md §10.4: scope the CSS of each block. Problems are located in the .jasx file.
        String scope = scopeName(file, structure);
        List<String> css = new ArrayList<>();
        for (JasxStyleBlock block : allBlocks) {
            ScopedCss.Result scoped = ScopedCss.scope(block.css(), scope);
            int contentStart = block.start() + OPEN_TAG_LENGTH;
            for (ScopedCss.Problem problem : scoped.problems()) {
                errors.add(Diagnostic.error("JASX2014", problem.message(),
                        contentStart + problem.start(), contentStart + problem.end()));
            }
            if (!scoped.css().isEmpty()) {
                css.add(scoped.css());
            }
        }

        if (!errors.isEmpty()) {
            return new Result(null, errors);
        }

        // semantics.md §10.5: the style class is the file class, or the top-level class of the first block.
        JavaStructure.TypeDecl styleClass = structure.fileClass;
        int fieldOffset = structure.afterImports;
        if (styleClass == null) {
            styleClass = topLevel(structure.enclosingType(first.start()));
            if (styleClass == null) {
                // Markup outside any class: not valid Java, and javac will say so. Nothing to scope against.
                return new Result(null, List.of(error(first, "JASX2015", "a style block must be inside a class")));
            }
            fieldOffset = styleClass.bodyStart + 1; // right after the '{' of the class body
        }
        return new Result(new Plan(styleClass.qualifiedName, scope, String.join("\n", css), fieldOffset), errors);
    }

    /** Adds the style blocks in {@code node} to {@code into}: in markup children, at any depth. */
    private static void collect(Node node, List<JasxStyleBlock> into) {
        switch (node) {
            case JasxStyleBlock block -> into.add(block);
            case JasxElement element -> {
                for (JasxAttribute attribute : element.openingElement().attributes()) {
                    if (attribute.value() != null) {
                        collect(attribute.value(), into);
                    }
                }
                for (Child child : element.children()) {
                    collect(child, into);
                }
            }
            case JasxFragment fragment -> {
                for (Child child : fragment.children()) {
                    collect(child, into);
                }
            }
            case JasxExpressionContainer container -> collect(container.expression(), into);
            case JavaExpression expression -> {
                for (Segment segment : expression.segments()) {
                    collect(segment, into);
                }
            }
            default -> {} // Java code, text, names, ...
        }
    }

    /** The outermost class that contains {@code type} (itself if it is top-level), or null. */
    private static JavaStructure.TypeDecl topLevel(JavaStructure.TypeDecl type) {
        while (type != null && type.parent != null) {
            type = type.parent;
        }
        return type;
    }

    /**
     * semantics.md §10.3: {@code jx-} and the first 8 hex digits of the SHA-256 of the file's qualified name
     * ({@code app.ui.Layout}).
     */
    static String scopeName(SourceFile file, JavaStructure structure) {
        String qualifiedName = structure.packageName.isEmpty()
                ? file.baseName()
                : structure.packageName + "." + file.baseName();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(qualifiedName.getBytes(StandardCharsets.UTF_8));
            return "jx-" + HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime has SHA-256", e);
        }
    }

    /** An error located at the {@code <style>} tag of {@code block}. */
    private static Diagnostic error(JasxStyleBlock block, String code, String message) {
        return Diagnostic.error(code, message, block.start(), block.start() + OPEN_TAG_LENGTH);
    }
}
