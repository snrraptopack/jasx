package jasx.parser.ast;

import java.util.List;

/**
 * The JASX AST (jasx-spec/ast.md). Every node carries its source range {@code [start, end)} in UTF-16
 * code units; {@code loc} is derived from the range when serializing (see {@link jasx.parser.LineMap}).
 */
public final class Ast {
    private Ast() {}

    public sealed interface Node {
        int start();

        int end();
    }

    /** A member of {@code JasxCompilationUnit.segments} or {@code JavaExpression.segments}. */
    public sealed interface Segment extends Node {}

    /** A member of {@code children}. */
    public sealed interface Child extends Node {}

    /** A value of {@code JasxAttribute.value}. */
    public sealed interface AttributeValue extends Node {}

    /** An element or fragment. */
    public sealed interface Markup extends Segment, Child, AttributeValue {}

    public sealed interface ElementName extends Node {}

    public sealed interface AttributeName extends Node {}

    public sealed interface Expression extends Node {}

    public record JasxCompilationUnit(int start, int end, String sourceFile, List<Segment> segments)
            implements Node {}

    public record JavaCode(int start, int end, String source) implements Segment {}

    /**
     * A style block, {@code <style>css</style>} (grammar.md §7.3). Appears in
     * {@code JasxCompilationUnit.segments}, or in {@code children} when written inside markup. {@code css}
     * is the text between the tags, exactly as written.
     */
    public record JasxStyleBlock(int start, int end, String css) implements Segment, Child {}

    public record JavaExpression(int start, int end, List<Segment> segments) implements Expression {}

    public record JasxEmptyExpression(int start, int end, String source) implements Expression {}

    public record JasxElement(
            int start,
            int end,
            JasxOpeningElement openingElement,
            List<Child> children,
            JasxClosingElement closingElement)
            implements Markup {}

    public record JasxOpeningElement(
            int start, int end, ElementName name, List<JasxAttribute> attributes, boolean selfClosing)
            implements Node {}

    public record JasxClosingElement(int start, int end, ElementName name) implements Node {}

    public record JasxFragment(
            int start,
            int end,
            JasxOpeningFragment openingFragment,
            List<Child> children,
            JasxClosingFragment closingFragment)
            implements Markup {}

    public record JasxOpeningFragment(int start, int end) implements Node {}

    public record JasxClosingFragment(int start, int end) implements Node {}

    public record JasxIdentifier(int start, int end, String name) implements ElementName, AttributeName {}

    public record JasxQualifiedName(int start, int end, ElementName qualifier, JasxIdentifier name)
            implements ElementName {}

    public record JasxNamespacedName(int start, int end, JasxIdentifier namespace, JasxIdentifier name)
            implements AttributeName {}

    /** {@code value} is {@code null} for a shorthand attribute ({@code <input disabled />}). */
    public record JasxAttribute(int start, int end, AttributeName name, AttributeValue value) implements Node {}

    public record JasxStringLiteral(int start, int end, String value, String raw) implements AttributeValue {}

    public record JasxText(int start, int end, String value, String raw) implements Child {}

    public record JasxExpressionContainer(int start, int end, Expression expression)
            implements Child, AttributeValue {}
}
