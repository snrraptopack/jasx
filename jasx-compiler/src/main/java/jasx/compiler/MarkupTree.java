package jasx.compiler;

import java.util.List;

/**
 * The markup of a {@code .jasx} file as a target's lowering sees it (semantics.md §7.4): every markup
 * expression after analysis, with children normalized (§3), components resolved and checked (§5), attributes
 * checked against the schema and classified as static, dynamic or handler (§8).
 *
 * <p>The compiler builds this tree, reporting every JASX error on the way, and then hands each markup
 * expression to the target's {@link MarkupLowering}, which writes Java through a {@link LoweringOutput}. The
 * user's own Java code inside the markup (expressions, statement containers, component calls) is not text the
 * lowering can change: it is a {@link Code} or a {@link Markup}, which the lowering places by passing it to the
 * output, exactly once. That is how every target keeps the guarantees of semantics.md §6.5–§6.7 (Java code
 * unchanged, lines preserved, mapped diagnostics) without implementing them.
 *
 * <p>Positions ({@code start}, {@code end}, {@code closeAnchor}) are source offsets in the {@code .jasx} file.
 * A lowering passes them as the anchor of the text it writes, so that the text lands on the right line.
 */
public final class MarkupTree {
    private MarkupTree() {}

    // ---------------------------------------------------------------------------------------------
    // Children (semantics.md §3)
    // ---------------------------------------------------------------------------------------------

    /** A child value after normalization (semantics.md §3): text, an expression, a statement, or markup. */
    public sealed interface Child permits Text, Dynamic, Statement, Markup {
        /** Where the child starts in the source. */
        int start();

        /** Where the child ends in the source (exclusive). */
        int end();
    }

    /**
     * A text child, normalized (semantics.md §3.1). A static position (§8).
     *
     * @param start the first non-whitespace character of the text
     */
    public record Text(String text, int start, int end) implements Child {
        /** The text as a Java string literal, with quotes. */
        public String javaLiteral() {
            return Lowering.javaString(text);
        }
    }

    /**
     * An expression child {@code {expression}}: a dynamic child (semantics.md §8).
     *
     * @param expression the Java expression, without the braces
     * @param start the position of {@code {}
     * @param end the position after {@code }}; {@code end - 1} is the anchor of text written after it
     */
    public record Dynamic(Code expression, int start, int end) implements Child {}

    /**
     * A statement container child such as {@code {for (T t : ts) { <li>{t}</li> }}}: a dynamic child
     * (semantics.md §3.5, §8).
     *
     * @param value an expression that runs the statement and evaluates to the list of children its markup
     *     statements produced (semantics.md §6.8); its static type is {@code java.lang.Object}, uncast
     */
    public record Statement(Code value, int start, int end) implements Child {}

    // ---------------------------------------------------------------------------------------------
    // Markup
    // ---------------------------------------------------------------------------------------------

    /**
     * A markup expression: an element or fragment that a lowering turns into one Java expression. A nested
     * markup expression is written with {@link LoweringOutput#markup}, which calls the lowering for it.
     */
    public sealed interface Markup extends Child permits Intrinsic, Fragment, Keyed, ComponentCall {}

    /**
     * An intrinsic element such as {@code <div class="card">...</div>} (semantics.md §6.3).
     *
     * @param tag the tag name
     * @param builderClass the fully qualified attribute builder class for the tag (semantics.md §6.3); the
     *     generic {@code Custom} builder for a custom element the schema doesn't list
     * @param attributes the attributes in source order, without {@code key}
     * @param children the normalized children
     * @param styleSheet the reference to the file's style sheet field if the file has one, otherwise null
     *     (semantics.md §10.5)
     * @param scoped whether one of the file's scoped compounds may match the element (semantics.md §10.5);
     *     false without a style sheet
     * @param closeAnchor the anchor for text written after the element: its closing tag, or the {@code >} of
     *     {@code />}
     */
    public record Intrinsic(String tag, String builderClass, List<Attribute> attributes, List<Child> children,
            String styleSheet, boolean scoped, int start, int end, int closeAnchor) implements Markup {}

    /**
     * A fragment {@code <>...</>}.
     *
     * @param closeAnchor the anchor for text written after the fragment: its closing {@code </>}
     */
    public record Fragment(List<Child> children, int start, int end, int closeAnchor) implements Markup {}

    /**
     * An element with a {@code key} attribute (semantics.md §4.1, §6.4). The key is not one of the
     * element's attributes or props.
     *
     * @param key a string literal or an expression
     * @param element the element without its key: an {@link Intrinsic} or a {@link ComponentCall}
     */
    public record Keyed(Value key, Markup element, int start, int end, int closeAnchor) implements Markup {}

    /**
     * A component element such as {@code <Card title="t" />}, which always lowers to a direct call
     * (semantics.md §5.6). The compiler writes the call; a lowering only places it, with
     * {@link LoweringOutput#markup}. Its children are converted to elements by
     * {@link MarkupLowering#componentChild}, and markup in its props is lowered by the target.
     */
    public static final class ComponentCall implements Markup {
        private final String className;
        private final String method;
        private final int start;
        private final int end;
        /** The arguments in parameter order; written by the compiler (Lowering). */
        final List<Lowering.Argument> arguments;
        final int closeAnchor;

        ComponentCall(String className, String method, List<Lowering.Argument> arguments, int start, int end,
                int closeAnchor) {
            this.className = className;
            this.method = method;
            this.arguments = arguments;
            this.start = start;
            this.end = end;
            this.closeAnchor = closeAnchor;
        }

        /** The fully qualified class of the component, such as {@code app.Page}. */
        public String className() {
            return className;
        }

        /** The component's method, such as {@code Card}. */
        public String method() {
            return method;
        }

        @Override
        public int start() {
            return start;
        }

        @Override
        public int end() {
            return end;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Attributes (semantics.md §4, §8)
    // ---------------------------------------------------------------------------------------------

    /** The classification of an intrinsic attribute's value (semantics.md §8). */
    public enum AttributeKind {
        /** A string literal, a shorthand attribute ({@code disabled}), or markup. */
        STATIC,
        /** An expression container on an attribute whose schema kind is not {@code event}. */
        DYNAMIC,
        /** An expression container on an attribute of schema kind {@code event}. */
        HANDLER
    }

    /**
     * An attribute of an intrinsic element.
     *
     * @param name the attribute name as written, such as {@code class} or {@code data-id}
     * @param method the builder method: the schema's method ({@code className}), or {@code attr} when the
     *     attribute is passed by name
     * @param byName whether it is passed by name, as {@code .attr("data-id", value)}: names matched by a
     *     schema pattern, namespaced names, attributes of custom elements, and unknown attributes
     */
    public record Attribute(String name, String method, boolean byName, AttributeKind kind, Value value,
            int start, int end) {
        /** The attribute name as a Java string literal, with quotes. */
        public String nameLiteral() {
            return Lowering.javaString(name);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Values and code
    // ---------------------------------------------------------------------------------------------

    /** The value of an attribute, a prop or a key. Written with {@link LoweringOutput#value}. */
    public sealed interface Value permits Literal, Expression, MarkupValue {}

    /**
     * A value written as Java text: a string literal (with quotes and Java escapes), {@code true} for a
     * shorthand attribute, or {@code null} for an empty container.
     *
     * @param java the Java text
     * @param string the string value of a string literal, otherwise null
     * @param anchor where the value is in the source
     */
    public record Literal(String java, String string, int anchor) implements Value {}

    /**
     * An expression container {@code {expression}} as a value. {@link LoweringOutput#value} writes it in
     * parentheses.
     *
     * @param start the position of {@code {}
     * @param end the position after {@code }}
     */
    public record Expression(Code expression, int start, int end) implements Value {}

    /** Markup as a value, such as {@code icon=<Icon />}. */
    public record MarkupValue(Markup markup) implements Value {}

    /**
     * The user's Java code inside markup: an expression, or the generated form of a statement container,
     * with any markup inside it. A lowering can't read or change it, only place it, with
     * {@link LoweringOutput#code}, exactly once: so the code is copied byte for byte (semantics.md §6.1),
     * keeps its lines (§6.5), and has its diagnostics mapped (§6.6).
     */
    public static final class Code {
        /** What to write, in order. */
        final List<Lowering.Part> parts;
        private final int start;
        private final int end;

        Code(List<Lowering.Part> parts, int start, int end) {
            this.parts = parts;
            this.start = start;
            this.end = end;
        }

        /** Where the code starts in the source. */
        public int start() {
            return start;
        }

        /** Where the code ends in the source (exclusive). */
        public int end() {
            return end;
        }
    }
}
