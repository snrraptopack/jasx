package jasx.compiler;

import java.util.List;

/**
 * Factory lowering (semantics.md §6), the standard {@link MarkupLowering}: markup becomes calls on the
 * target's factory class {@code F}.
 *
 * <pre>
 *   &lt;ul class="list"&gt;{items}&lt;/ul&gt;   →  F.h("ul", jasx.html.attrs.Ul.create().className("list"), (java.lang.Object) (items))
 *   &lt;&gt;a&lt;/&gt;                       →  F.fragment("a")
 *   &lt;li key={id}&gt;x&lt;/li&gt;           →  F.keyed((id), F.h("li", null, "x"))
 * </pre>
 *
 * <p>Every position has its own method, so that a target can extend this class and change only what it
 * needs: for example override {@link #dynamicChild} to pass each dynamic child as a function, or
 * {@link #attributeValue} to do the same for dynamic attributes (semantics.md §8).
 */
public class FactoryLowering implements MarkupLowering {
    /** Cast for children, so that a lone null or array is one variadic argument (semantics.md §6.7). */
    protected static final String OBJECT_CAST = "(java.lang.Object) ";

    /** The target being compiled for. */
    protected final Target target;
    /** The fully qualified factory class, such as {@code jasx.html.Html}. */
    protected final String factory;

    public FactoryLowering(Target target) {
        this.target = target;
        this.factory = target.factory();
    }

    // ---------------------------------------------------------------------------------------------
    // Markup
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code <a href="/x">go</a>}  →  {@code F.h("a", jasx.html.attrs.A.create().href("/x"), "go")}, wrapped
     * in {@code F.scoped(styles, ...)} or {@code F.unscoped(styles, ...)} in a file with a style sheet
     * (semantics.md §10.5).
     */
    @Override
    public void intrinsic(MarkupTree.Intrinsic element, LoweringOutput out) {
        boolean wrapped = element.styleSheet() != null;
        if (wrapped) {
            String function = element.scoped() ? ".scoped(" : ".unscoped(";
            out.write(factory + function + element.styleSheet() + ", ", element.start());
        }

        out.write(factory + ".h(" + Lowering.javaString(element.tag()) + ", ", element.start());
        attributes(element, out);
        for (MarkupTree.Child child : element.children()) {
            out.write(", ", child.start());
            child(child, out);
        }
        out.write(wrapped ? "))" : ")", element.closeAnchor());
    }

    /** {@code <>a{b}</>}  →  {@code F.fragment("a", (java.lang.Object) (b))} */
    @Override
    public void fragment(MarkupTree.Fragment fragment, LoweringOutput out) {
        out.write(factory + ".fragment(", fragment.start());
        List<MarkupTree.Child> children = fragment.children();
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                out.write(", ", children.get(i).start());
            }
            child(children.get(i), out);
        }
        out.write(")", fragment.closeAnchor());
    }

    /** {@code <li key={id}>x</li>}  →  {@code F.keyed((id), F.h("li", null, "x"))} (semantics.md §6.4) */
    @Override
    public void keyed(MarkupTree.Keyed keyed, LoweringOutput out) {
        out.write(factory + ".keyed(", keyed.start());
        out.value(keyed.key());
        out.write(", ", keyed.start());
        out.markup(keyed.element());
        out.write(")", keyed.closeAnchor());
    }

    // ---------------------------------------------------------------------------------------------
    // Attributes
    // ---------------------------------------------------------------------------------------------

    /**
     * The attributes argument of {@code F.h}: {@code null} without attributes, otherwise a builder chain
     * such as {@code jasx.html.attrs.Button.create().className("x").disabled(true)} (semantics.md §6.3).
     */
    protected void attributes(MarkupTree.Intrinsic element, LoweringOutput out) {
        if (element.attributes().isEmpty()) {
            out.write("null", element.start());
            return;
        }
        out.write(element.builderClass() + ".create()", element.start());
        for (MarkupTree.Attribute attribute : element.attributes()) {
            attribute(attribute, out);
        }
    }

    /** One builder call: {@code .className("x")}, or {@code .attr("data-id", 7)} for one passed by name. */
    protected void attribute(MarkupTree.Attribute attribute, LoweringOutput out) {
        String nameArgument = attribute.byName() ? attribute.nameLiteral() + ", " : "";
        out.write("." + attribute.method() + "(" + nameArgument, attribute.start());
        attributeValue(attribute, out);
        out.write(")", attribute.end());
    }

    /** The value of an attribute, whatever its kind (static, dynamic or handler, semantics.md §8). */
    protected void attributeValue(MarkupTree.Attribute attribute, LoweringOutput out) {
        out.value(attribute.value());
    }

    // ---------------------------------------------------------------------------------------------
    // Children
    // ---------------------------------------------------------------------------------------------

    /** A child of an intrinsic element or fragment, as one variadic argument of {@code h} or {@code fragment}. */
    protected void child(MarkupTree.Child child, LoweringOutput out) {
        switch (child) {
            case MarkupTree.Text text -> out.write(text.javaLiteral(), text.start());
            case MarkupTree.Dynamic dynamic -> dynamicChild(dynamic, out);
            case MarkupTree.Statement statement -> statementChild(statement, out);
            case MarkupTree.Markup markup -> out.markup(markup);
        }
    }

    /** {@code {expression}}  →  {@code (java.lang.Object) (expression)} */
    protected void dynamicChild(MarkupTree.Dynamic child, LoweringOutput out) {
        out.write(OBJECT_CAST + "(", child.start());
        out.code(child.expression());
        out.write(")", child.end() - 1);
    }

    /** {@code {for ...}}  →  {@code (java.lang.Object) switch (0) { ... }} (semantics.md §6.8) */
    protected void statementChild(MarkupTree.Statement child, LoweringOutput out) {
        out.write(OBJECT_CAST, child.start());
        out.code(child.value());
    }

    /**
     * A child converted to an {@code Element} for a component parameter (semantics.md §5.5): markup as it
     * is, text through {@code F.text}, and an expression or statement through {@code F.fragment}, so that
     * null, lists and Optionals keep their meaning.
     */
    @Override
    public void componentChild(MarkupTree.Child child, LoweringOutput out) {
        switch (child) {
            case MarkupTree.Markup markup -> out.markup(markup);
            case MarkupTree.Text text -> out.write(factory + ".text(" + text.javaLiteral() + ")", text.start());
            case MarkupTree.Dynamic dynamic -> {
                out.write(factory + ".fragment(", dynamic.start());
                dynamicChild(dynamic, out);
                out.write(")", dynamic.end() - 1);
            }
            case MarkupTree.Statement statement -> {
                out.write(factory + ".fragment(", statement.start());
                statementChild(statement, out);
                out.write(")", statement.end() - 1);
            }
        }
    }
}
