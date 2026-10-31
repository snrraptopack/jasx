package jasx.compiler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import jasx.parser.Diagnostic;
import jasx.parser.ParseResult;
import jasx.parser.ast.Ast.JasxStyleBlock;
import jasx.parser.ast.Ast.Segment;

/**
 * Checks the style block of a {@code .jasx} file and prepares its lowering (semantics.md §10).
 *
 * <p>The parser finds style blocks and {@link JavaStructure} finds the class each one is in. This class
 * checks the rules of semantics.md §10.1 and §10.6 (JASX2015, JASX2016, JASX2106), scopes the CSS with
 * {@link ScopedCss} (JASX2014), and returns a {@link Plan} that {@link Lowering} follows.
 */
final class StyleBlocks {
    private StyleBlocks() {}

    /** The length of {@code <style>}, where the CSS content starts. */
    private static final int OPEN_TAG_LENGTH = "<style>".length();

    /**
     * How to lower the file's style block.
     *
     * @param block the style block, which is replaced by a field declaration
     * @param ownerClass the fully qualified class the field is declared in, such as {@code app.Ui}
     * @param scope the scope name, such as {@code jx-1a2b3c4d} (semantics.md §10.3)
     * @param css the scoped CSS (semantics.md §10.4)
     */
    record Plan(JasxStyleBlock block, String ownerClass, String scope, String css) {
        /** The name of the generated field (semantics.md §10.5). */
        static final String FIELD = "jasx$styles";

        /** How generated code refers to the style sheet: {@code app.Ui.jasx$styles}. */
        String fieldReference() {
            return ownerClass + "." + FIELD;
        }
    }

    /** The plan for the file's style block (null if it has none), and the problems found. */
    record Result(Plan plan, List<Diagnostic> errors) {}

    static Result check(SourceFile file, ParseResult parsed, JavaStructure structure, Target target) {
        List<JasxStyleBlock> blocks = new ArrayList<>();
        for (Segment segment : parsed.unit().segments()) {
            if (segment instanceof JasxStyleBlock block) {
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) {
            return new Result(null, List.of());
        }

        List<Diagnostic> errors = new ArrayList<>();
        JasxStyleBlock first = blocks.get(0);

        // semantics.md §10.6: the target must support style blocks.
        if (!target.styles()) {
            errors.add(error(first, "JASX2106", "target '" + target.id() + "' does not support style blocks"));
        }

        // semantics.md §10.1: one per file.
        for (JasxStyleBlock extra : blocks.subList(1, blocks.size())) {
            errors.add(error(extra, "JASX2016", "a file can have only one style block"));
        }

        // semantics.md §10.1: where a field could be declared. The structure found the same blocks in order.
        JavaStructure.TypeDecl owner = structure.styleBlocks.get(0).owner();
        if (owner == null) {
            errors.add(error(first, "JASX2015", "a style block must be where a field could be declared: in the body"
                    + " of a class, or at the top level of a file that declares fields or methods there"));
        }

        // semantics.md §10.4: scope the CSS. Problems are located in the .jasx file.
        String scope = scopeName(file, structure);
        ScopedCss.Result scoped = ScopedCss.scope(first.css(), scope);
        int contentStart = first.start() + OPEN_TAG_LENGTH;
        for (ScopedCss.Problem problem : scoped.problems()) {
            errors.add(Diagnostic.error("JASX2014", problem.message(),
                    contentStart + problem.start(), contentStart + problem.end()));
        }

        if (!errors.isEmpty()) {
            return new Result(null, errors);
        }
        return new Result(new Plan(first, owner.qualifiedName, scope, scoped.css()), errors);
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
