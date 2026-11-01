package jasx.compiler;

/**
 * Where a {@link MarkupLowering} writes the Java code of a markup expression (semantics.md §7.4).
 *
 * <p>The output keeps lines aligned (semantics.md §6.5): before writing text for a source position, it adds
 * line breaks until the generated file is on that position's line. Text should therefore be written in source
 * order, each piece anchored at the source position it belongs to (the {@code start}, {@code end} and
 * {@code closeAnchor} of the tree's nodes). Text written for an earlier position is still correct Java; it
 * only ends up on a later line.
 *
 * <p>Every {@link MarkupTree.Code} and every nested {@link MarkupTree.Markup} of the tree must be written
 * exactly once, through {@link #code}, {@link #markup} or {@link #value}. Writing one twice throws at once;
 * leaving one out throws when the markup expression is finished.
 */
public interface LoweringOutput {
    /**
     * Writes generated Java text for the source position {@code anchor}. The text must not contain line
     * breaks, so that the lines of the generated file keep matching the source.
     */
    void write(String java, int anchor);

    /** Writes the user's Java code, unchanged (markup inside it is lowered by the target). */
    void code(MarkupTree.Code code);

    /**
     * Writes a nested markup expression: calls the lowering for an intrinsic element, a fragment, a keyed
     * element or a component.
     */
    void markup(MarkupTree.Markup markup);

    /**
     * Writes the call of a component, as semantics.md §5.6 requires: {@code app.Page.Card("t", ...)}, its
     * arguments in parameter order. For {@link MarkupLowering#component}, which must call it exactly once.
     */
    void call(MarkupTree.ComponentCall call);

    /**
     * Writes a value: a literal as it is, an expression in parentheses ({@code (expression)}), and markup
     * as {@link #markup} does.
     */
    void value(MarkupTree.Value value);
}
