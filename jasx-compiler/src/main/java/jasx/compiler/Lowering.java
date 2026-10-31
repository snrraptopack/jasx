package jasx.compiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jasx.compiler.ComponentIndex.Children;
import jasx.compiler.ComponentIndex.Component;
import jasx.compiler.ComponentIndex.Prop;
import jasx.compiler.ComponentIndex.PropKind;
import jasx.parser.Diagnostic;
import jasx.parser.JasxParser;
import jasx.parser.LineMap;
import jasx.parser.ast.Ast.Child;
import jasx.parser.ast.Ast.ElementName;
import jasx.parser.ast.Ast.JasxAttribute;
import jasx.parser.ast.Ast.JasxCompilationUnit;
import jasx.parser.ast.Ast.JasxElement;
import jasx.parser.ast.Ast.JasxEmptyExpression;
import jasx.parser.ast.Ast.JasxExpressionContainer;
import jasx.parser.ast.Ast.JasxFragment;
import jasx.parser.ast.Ast.JasxIdentifier;
import jasx.parser.ast.Ast.JasxNamespacedName;
import jasx.parser.ast.Ast.JasxStringLiteral;
import jasx.parser.ast.Ast.JasxStyleBlock;
import jasx.parser.ast.Ast.JasxText;
import jasx.parser.ast.Ast.JavaCode;
import jasx.parser.ast.Ast.JavaExpression;
import jasx.parser.ast.Ast.Markup;
import jasx.parser.ast.Ast.Segment;

/**
 * Factory lowering of one {@code .jasx} file (semantics.md §6): Java code is copied byte for byte, and
 * every markup expression is replaced by plain Java calls.
 *
 * <p>For example, with the html target ({@code F} = {@code jasx.html.Html}):
 *
 * <pre>
 *   &lt;ul class="list"&gt;{items}&lt;/ul&gt;      →  F.h("ul", jasx.html.attrs.Ul.create().className("list"), (java.lang.Object) (items))
 *   &lt;&gt;a&lt;/&gt;                          →  F.fragment("a")
 *   &lt;Card title="t" /&gt;                →  app.Page.Card("t")
 *   &lt;li key={id}&gt;x&lt;/li&gt;              →  F.keyed((id), F.h("li", null, "x"))
 * </pre>
 *
 * <p>All output goes through an {@link Emitter}, which keeps the generated code on the same lines as the
 * markup it came from. Every {@code emit} call therefore names the source position the code belongs to.
 *
 * <p>Problems found here (unknown attributes, missing props, ...) are collected in {@link #diagnostics()}.
 * Lowering continues after an error, so that one run reports as many problems as possible.
 */
final class Lowering {
    /** Cast for expression children, so that a lone null or array is one variadic argument (§6.7). */
    private static final String OBJECT_CAST = "(java.lang.Object) ";

    private static final String ELEMENT = "jasx.runtime.Element";

    // ---------------------------------------------------------------------------------------------
    // Normalized children (semantics.md §3)
    // ---------------------------------------------------------------------------------------------

    /**
     * A child after normalization: whitespace-only text is gone, text is trimmed, and empty expression
     * containers ({@code {}} and comments) are removed.
     */
    private sealed interface ChildValue {
        int start();

        int end();
    }

    /** Normalized text. {@code start} is the first non-whitespace character, used to keep lines aligned. */
    private record TextValue(String text, int start, int end) implements ChildValue {}

    /** A {@code {expression}} child. */
    private record ExpressionValue(JasxExpressionContainer container) implements ChildValue {
        @Override
        public int start() {
            return container.start();
        }

        @Override
        public int end() {
            return container.end();
        }

        JavaExpression expression() {
            return (JavaExpression) container.expression();
        }
    }

    /** A nested element or fragment. */
    private record MarkupValue(Markup markup) implements ChildValue {
        @Override
        public int start() {
            return markup.start();
        }

        @Override
        public int end() {
            return markup.end();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------------

    private final SourceFile file;
    private final String src;
    private final Target target;
    private final Schema schema;
    /** The fully qualified factory class, such as {@code jasx.html.Html}. */
    private final String factory;
    private final Resolver resolver;
    private final Emitter out;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    /** The file's style sheet, or null. If set, every intrinsic element is wrapped (semantics.md §10.5). */
    private StyleBlocks.Plan styles;
    /** The component tags resolved so far, for editor tooling (CompilationResult.uses). */
    private final List<CompilationResult.Use> uses = new ArrayList<>();

    Lowering(SourceFile file, LineMap lines, Target target, Resolver resolver) {
        this.file = file;
        this.src = file.content();
        this.target = target;
        this.schema = target.schema();
        this.factory = target.factory();
        this.resolver = resolver;
        this.out = new Emitter(src, lines);
    }

    List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    List<CompilationResult.Use> uses() {
        return uses;
    }

    // ---------------------------------------------------------------------------------------------
    // The file
    // ---------------------------------------------------------------------------------------------

    /**
     * Text inserted into the Java code at a source offset: {@code @jasx.Component} before a component
     * (semantics.md §5.1), the parts of a file class (§9.4), and the style sheet field (§10.5). Insertions at
     * the same offset are written in the order of their {@code order}, so that, for example, the file class
     * header comes before the style sheet, which comes before the first member's {@code @jasx.Component},
     * which comes before its {@code static}.
     */
    record Insertion(int offset, int order, String text) {
        static final int CLASS_HEADER = 0;
        static final int STYLE_SHEET = 1;
        static final int ANNOTATION = 2;
        static final int MODIFIER = 3;
        static final int CLASS_END = 4;
    }

    /**
     * Lowers the whole file, adding {@code insertions} to its Java code.
     *
     * @param styles the file's style sheet, or null if it has no style block; its field is one of the
     *     insertions, and if it is set every intrinsic element is scoped
     */
    GeneratedFile lower(JasxCompilationUnit unit, List<Insertion> insertions, StyleBlocks.Plan styles) {
        this.styles = styles;
        List<Insertion> pending = new ArrayList<>(insertions);
        pending.sort(Comparator.comparingInt(Insertion::offset).thenComparingInt(Insertion::order));
        int next = 0;

        for (Segment segment : unit.segments()) {
            // Insertions where markup or a style block starts (such as the style sheet field right after
            // "class T {" in "class T {<style>...") belong before it.
            boolean isJava = segment instanceof JavaCode;
            while (!isJava && next < pending.size() && pending.get(next).offset() <= segment.start()) {
                out.emit(pending.get(next).text(), pending.get(next).offset());
                next++;
            }

            if (segment instanceof Markup markup) {
                markup(markup);
                continue;
            }
            if (segment instanceof JasxStyleBlock) {
                continue; // removed; its CSS is in the style sheet field (semantics.md §10.5)
            }

            // A Java segment: copy it, writing each insertion that falls inside it at its place.
            int copiedUpTo = segment.start();
            while (next < pending.size() && pending.get(next).offset() < segment.end()) {
                Insertion insertion = pending.get(next);
                next++;
                if (insertion.offset() < copiedUpTo) {
                    continue; // inside markup that came before this segment: can't happen for valid input
                }
                out.copy(copiedUpTo, insertion.offset());
                out.emit(insertion.text(), insertion.offset());
                copiedUpTo = insertion.offset();
            }
            out.copy(copiedUpTo, segment.end());
        }

        // Insertions at the very end of the file, such as the file class's closing brace.
        for (; next < pending.size(); next++) {
            out.emit(pending.get(next).text(), pending.get(next).offset());
        }

        return new GeneratedFile(file, file.javaPath(), out.text(), out.spans());
    }

    // ---------------------------------------------------------------------------------------------
    // Elements and fragments
    // ---------------------------------------------------------------------------------------------

    /** Lowers an element or a fragment. */
    private void markup(Markup markup) {
        switch (markup) {
            case JasxFragment fragment -> fragment(fragment);
            case JasxElement element -> element(element);
        }
    }

    /** {@code <>a{b}</>}  →  {@code F.fragment("a", (java.lang.Object) (b))} */
    private void fragment(JasxFragment fragment) {
        out.emit(factory + ".fragment(", fragment.start());

        List<ChildValue> children = normalize(fragment.children());
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                out.emit(", ", children.get(i).start());
            }
            child(children.get(i));
        }

        int closeAt = fragment.closingFragment() != null ? fragment.closingFragment().start() : fragment.end();
        out.emit(")", closeAt);
    }

    /**
     * Lowers an element: intrinsic ({@code <div>}) or component ({@code <Card>}). A {@code key} attribute
     * wraps the result in {@code F.keyed(key, ...)} and is not passed on (semantics.md §4.1, §6.4).
     */
    private void element(JasxElement element) {
        JasxAttribute key = null;
        List<JasxAttribute> attributes = new ArrayList<>();
        for (JasxAttribute attribute : element.openingElement().attributes()) {
            if (isKey(attribute)) {
                key = attribute;
            } else {
                attributes.add(attribute);
            }
        }

        if (key != null) {
            out.emit(factory + ".keyed(", element.start());
            keyValue(key);
            out.emit(", ", element.start());
        }

        if (isIntrinsic(element.openingElement().name())) {
            intrinsic(element, attributes);
        } else {
            component(element, attributes);
        }

        if (key != null) {
            out.emit(")", closingAnchor(element));
        }
    }

    private static boolean isKey(JasxAttribute attribute) {
        return attribute.name() instanceof JasxIdentifier id && id.name().equals("key");
    }

    /** semantics.md §2: a lowercase name, or one containing '-', is an intrinsic element. */
    private static boolean isIntrinsic(ElementName name) {
        if (!(name instanceof JasxIdentifier id)) {
            return false; // qualified names (Ui.Card) are always components
        }
        boolean customElement = id.name().contains("-");
        boolean lowercase = Character.isLowerCase(id.name().charAt(0));
        return customElement || lowercase;
    }

    /** The value of a key: a string literal or an expression (JASX2009 otherwise). */
    private void keyValue(JasxAttribute key) {
        if (key.value() instanceof JasxStringLiteral string) {
            out.emit(javaString(string.value()), string.start());
            return;
        }
        if (key.value() instanceof JasxExpressionContainer container
                && container.expression() instanceof JavaExpression expression) {
            parenthesized(container, expression);
            return;
        }
        error("JASX2009", "a key must be a string literal or an expression", key.start(), key.end());
        out.emit("null", key.start());
    }

    // ---------------------------------------------------------------------------------------------
    // Intrinsic elements (semantics.md §6.3)
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code <a href="/x">go</a>}  →  {@code F.h("a", jasx.html.attrs.A.create().href("/x"), "go")}
     *
     * <p>The tag and every attribute are checked against the target's schema.
     */
    private void intrinsic(JasxElement element, List<JasxAttribute> attributes) {
        JasxIdentifier name = (JasxIdentifier) element.openingElement().name();
        String tag = name.name();
        Schema.ElementSpec spec = schema.element(tag);

        // A tag the schema doesn't know is an error, unless it is a custom element (my-widget) and the
        // target accepts those.
        boolean isCustomElement = spec == null && tag.contains("-") && schema.customElements();
        if (spec == null && !isCustomElement) {
            error("JASX2105", "<" + tag + "> is not an element of target '" + target.id() + "'",
                    name.start(), name.end());
        }

        List<ChildValue> children = normalize(element.children());
        if (spec != null && spec.isVoid() && !children.isEmpty()) {
            ChildValue first = children.get(0);
            error("JASX2102", "<" + tag + "> is a void element and cannot have children", first.start(), first.end());
        }

        // In a file with a style sheet, the element is wrapped (§10.5): F.scoped(C.jasx$styles, F.h(...)) if
        // a scoped compound may match it, so that it gets the scope class, otherwise F.unscoped(...).
        boolean wrapped = styles != null;
        if (wrapped) {
            String function = mayBeStyled(tag, attributes) ? ".scoped(" : ".unscoped(";
            out.emit(factory + function + styles.fieldReference() + ", ", element.start());
        }

        // F.h("tag", attributes, children...)
        out.emit(factory + ".h(" + javaString(tag) + ", ", element.start());
        attributeBuilder(tag, spec, attributes, element.start());
        for (ChildValue child : children) {
            out.emit(", ", child.start());
            child(child);
        }
        out.emit(wrapped ? "))" : ")", closingAnchor(element));
    }

    /** Whether one of the file's scoped compounds may match this element (semantics.md §10.5). */
    private boolean mayBeStyled(String tag, List<JasxAttribute> attributes) {
        return styles.match().mayMatch(tag, attributeValue(attributes, "class"), attributeValue(attributes, "id"));
    }

    /** What the source says about the attribute {@code name}: absent, a string literal, or any value. */
    private static ScopeMatch.AttributeValue attributeValue(List<JasxAttribute> attributes, String name) {
        for (JasxAttribute attribute : attributes) {
            if (JasxParser.attributeNameText(attribute.name()).equals(name)) {
                return attribute.value() instanceof JasxStringLiteral literal
                        ? ScopeMatch.AttributeValue.literal(literal.value())
                        : ScopeMatch.AttributeValue.ANY; // an expression, or no value
            }
        }
        return ScopeMatch.AttributeValue.ABSENT;
    }

    /**
     * The attributes argument of {@code F.h}: {@code null} without attributes, otherwise a builder chain
     * such as {@code jasx.html.attrs.Button.create().className("x").disabled(true)}.
     */
    private void attributeBuilder(String tag, Schema.ElementSpec spec, List<JasxAttribute> attributes, int anchor) {
        if (attributes.isEmpty()) {
            out.emit("null", anchor);
            return;
        }

        // Unknown and custom elements use the generic builder, which only has .attr(name, value).
        boolean unknownElement = spec == null;
        String builderClass = unknownElement ? "Custom" : Schema.pascalCase(tag);
        out.emit(target.attributesPackage() + "." + builderClass + ".create()", anchor);

        for (JasxAttribute attribute : attributes) {
            attributeCall(tag, unknownElement, attribute);
        }
    }

    /**
     * One builder call for one attribute: {@code .className("x")}, or {@code .attr("data-id", 7)} for names
     * the schema accepts by pattern (data-*, aria-*, ...), namespaced names, and custom elements.
     */
    private void attributeCall(String tag, boolean unknownElement, JasxAttribute attribute) {
        String name = JasxParser.attributeNameText(attribute.name());

        boolean passByName = unknownElement
                || attribute.name() instanceof JasxNamespacedName
                || schema.matchesPattern(name);
        String method = "attr";

        if (!passByName) {
            Schema.AttributeSpec spec = schema.attribute(tag, name);
            if (spec == null) {
                error("JASX2101", "<" + tag + "> has no attribute '" + name + "' on target '" + target.id() + "'",
                        attribute.start(), attribute.end());
                passByName = true; // keep going, so that later errors are found too
            } else {
                method = spec.method();
                checkEventHandler(name, spec, attribute);
            }
        }

        String nameArgument = passByName ? javaString(name) + ", " : "";
        out.emit("." + method + "(" + nameArgument, attribute.start());
        attributeValue(attribute);
        out.emit(")", attribute.end());
    }

    /** On a target without Java event handlers, an event attribute only takes a string (JASX2103). */
    private void checkEventHandler(String name, Schema.AttributeSpec spec, JasxAttribute attribute) {
        boolean javaHandler = attribute.value() instanceof JasxExpressionContainer;
        if (spec.event() && javaHandler && !target.javaEventHandlers()) {
            error("JASX2103", "event attribute '" + name + "' accepts only a string on target '" + target.id()
                            + "', which has no Java event handlers",
                    attribute.value().start(), attribute.value().end());
        }
    }

    /**
     * The Java value of an attribute (semantics.md §4): {@code true} for a shorthand attribute, a string
     * literal, a parenthesized expression, or lowered markup.
     */
    private void attributeValue(JasxAttribute attribute) {
        switch (attribute.value()) {
            case null -> out.emit("true", attribute.start());
            case JasxStringLiteral string -> out.emit(javaString(string.value()), string.start());
            case JasxExpressionContainer container -> {
                if (container.expression() instanceof JavaExpression expression) {
                    parenthesized(container, expression);
                } else {
                    out.emit("null", container.start()); // {} is already a syntax error (JASX1008)
                }
            }
            case Markup markup -> markup(markup);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Components (semantics.md §5.4–§5.6)
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code <Card subtitle="s" title="t" />}  →  {@code app.Page.Card("t", "s")}
     *
     * <p>A direct static call. Arguments are in <em>parameter</em> order, whatever the attribute order;
     * absent optional props get {@code null} (or a primitive's default), absent default props get their
     * literal, and the {@code children} parameter gets the child content.
     */
    private void component(JasxElement element, List<JasxAttribute> attributes) {
        Resolver.Resolution resolution = resolver.resolve(element.openingElement().name(), element.start());
        if (resolution.error() != null) {
            diagnostics.add(resolution.error());
            out.emit("null", element.start());
            return;
        }
        Component component = resolution.component();
        recordUse(component, element);
        String tag = "<" + JasxParser.nameText(element.openingElement().name()) + ">";

        Map<String, JasxAttribute> givenProps = checkProps(tag, component, attributes);
        List<ChildValue> children = normalize(element.children());
        checkChildren(tag, component, children, element);
        checkRequiredProps(tag, component, givenProps, element);

        // Component.Method(arg1, arg2, ...)
        out.emit(component.className() + "." + component.method() + "(", element.start());
        List<Prop> params = component.params();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                out.emit(", ", element.start());
            }
            argument(params.get(i), givenProps, component.children(), children, element);
        }
        out.emit(")", closingAnchor(element));
    }

    /** Records the names of the opening and closing tags of {@code element} as uses of {@code component}. */
    private void recordUse(Component component, JasxElement element) {
        ElementName opening = element.openingElement().name();
        uses.add(new CompilationResult.Use(component, file.path(), opening.start(), opening.end()));
        if (element.closingElement() != null) {
            ElementName closing = element.closingElement().name();
            uses.add(new CompilationResult.Use(component, file.path(), closing.start(), closing.end()));
        }
    }

    /** Checks each attribute against the component's props; returns the valid ones by name. */
    private Map<String, JasxAttribute> checkProps(String tag, Component component, List<JasxAttribute> attributes) {
        Map<String, JasxAttribute> given = new LinkedHashMap<>();
        for (JasxAttribute attribute : attributes) {
            String name = JasxParser.attributeNameText(attribute.name());
            boolean isPlainName = attribute.name() instanceof JasxIdentifier;

            if (name.equals("children") && component.children() != Children.NONE) {
                error("JASX2008", "pass the children of " + tag + " as child content, not as an attribute",
                        attribute.start(), attribute.end());
            } else if (!isPlainName || component.prop(name) == null) {
                error("JASX2005", tag + " has no prop '" + name + "'", attribute.start(), attribute.end());
            } else {
                given.put(name, attribute);
            }
        }
        return given;
    }

    /** semantics.md §5.5: no children, exactly one child, or any number, depending on the component. */
    private void checkChildren(String tag, Component component, List<ChildValue> children, JasxElement element) {
        if (component.children() == Children.NONE && !children.isEmpty()) {
            ChildValue first = children.get(0);
            error("JASX2003", tag + " takes no children", first.start(), first.end());
        } else if (component.children() == Children.SINGLE && children.size() != 1) {
            error("JASX2004", tag + " takes exactly one child, but has " + children.size(),
                    element.start(), element.end());
        }
    }

    private void checkRequiredProps(String tag, Component component, Map<String, JasxAttribute> given, JasxElement element) {
        for (Prop prop : component.params()) {
            if (prop.kind() == PropKind.REQUIRED && !given.containsKey(prop.name())) {
                error("JASX2007", tag + " is missing the required prop '" + prop.name() + "'",
                        element.openingElement().start(), element.openingElement().end());
            }
        }
    }

    /** The argument for one parameter of a component call. */
    private void argument(Prop prop, Map<String, JasxAttribute> givenProps, Children childrenKind,
            List<ChildValue> children, JasxElement element) {
        if (prop.kind() == PropKind.CHILDREN) {
            componentChildren(childrenKind, children, element);
            return;
        }
        JasxAttribute given = givenProps.get(prop.name());
        if (given != null) {
            attributeValue(given);
        } else if (prop.kind() == PropKind.DEFAULT) {
            out.emit(prop.defaultValue(), element.start());
        } else {
            out.emit(absentValue(prop.type()), element.start());
        }
    }

    /**
     * The {@code children} argument: the single child as an Element, or
     * {@code java.util.List.<jasx.runtime.Element>of(child1, child2, ...)}.
     */
    private void componentChildren(Children kind, List<ChildValue> children, JasxElement element) {
        if (kind == Children.SINGLE) {
            if (children.size() == 1) {
                asElement(children.get(0));
            } else {
                out.emit("null", element.start()); // wrong count, already reported (JASX2004)
            }
            return;
        }

        out.emit("java.util.List.<" + ELEMENT + ">of(", element.start());
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                out.emit(", ", children.get(i).start());
            }
            asElement(children.get(i));
        }
        out.emit(")", closingAnchor(element));
    }

    /**
     * A child converted to an {@code Element} for a component parameter (semantics.md §5.5): markup as it
     * is, text through {@code F.text}, and an expression through {@code F.fragment}, so that null, lists and
     * Optionals keep their meaning.
     */
    private void asElement(ChildValue child) {
        switch (child) {
            case MarkupValue m -> markup(m.markup());
            case TextValue t -> out.emit(factory + ".text(" + javaString(t.text()) + ")", t.start());
            case ExpressionValue v -> {
                out.emit(factory + ".fragment(" + OBJECT_CAST + "(", v.start());
                expression(v.expression());
                out.emit("))", v.end() - 1);
            }
        }
    }

    /**
     * The value passed for an absent {@code @jasx.Optional} prop: null, or the default value of a primitive
     * type. Byte and short need a cast, because Java doesn't narrow {@code 0} for a method argument.
     */
    private static String absentValue(String type) {
        return switch (type) {
            case "boolean" -> "false";
            case "char" -> "'\\0'";
            case "byte" -> "(byte) 0";
            case "short" -> "(short) 0";
            case "int" -> "0";
            case "long" -> "0L";
            case "float" -> "0.0f";
            case "double" -> "0.0";
            default -> "null";
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Children and expressions
    // ---------------------------------------------------------------------------------------------

    /**
     * A child of an intrinsic element or fragment, as one variadic argument of {@code F.h} or
     * {@code F.fragment}: text as a string literal, an expression cast to Object, or markup.
     */
    private void child(ChildValue child) {
        switch (child) {
            case MarkupValue m -> markup(m.markup());
            case TextValue t -> out.emit(javaString(t.text()), t.start());
            case ExpressionValue v -> {
                out.emit(OBJECT_CAST + "(", v.start());
                expression(v.expression());
                out.emit(")", v.end() - 1);
            }
        }
    }

    /** {@code {expr}} as an attribute or prop value: {@code (expr)}. */
    private void parenthesized(JasxExpressionContainer container, JavaExpression expression) {
        out.emit("(", container.start());
        expression(expression);
        out.emit(")", container.end() - 1);
    }

    /** Copies a Java expression, lowering any markup nested inside it. */
    private void expression(JavaExpression expression) {
        for (Segment segment : expression.segments()) {
            if (segment instanceof Markup markup) {
                markup(markup);
            } else {
                out.copy(segment.start(), segment.end());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Children normalization (semantics.md §3)
    // ---------------------------------------------------------------------------------------------

    /** Turns the AST children into child values: text normalized, empty containers removed. */
    private List<ChildValue> normalize(List<Child> children) {
        List<ChildValue> values = new ArrayList<>();
        for (Child child : children) {
            switch (child) {
                case JasxText text -> {
                    String normalized = normalizeText(text.value());
                    if (normalized != null) {
                        int start = firstNonWhitespace(text.start(), text.end());
                        values.add(new TextValue(normalized, start, text.end()));
                    }
                }
                case JasxExpressionContainer container -> {
                    boolean isEmpty = container.expression() instanceof JasxEmptyExpression;
                    if (!isEmpty) {
                        values.add(new ExpressionValue(container));
                    }
                }
                case Markup markup -> values.add(new MarkupValue(markup));
                case JasxStyleBlock block -> {} // not a child value: its CSS is in the style sheet (§3.3)
            }
        }
        return values;
    }

    /**
     * The JSX text rule (semantics.md §3.1). Returns null if nothing is left.
     *
     * <p>The text is split into lines. Every line but the first loses its leading spaces and tabs, every
     * line but the last loses its trailing ones, empty lines are dropped, and the rest are joined with one
     * space. So indentation and line breaks between tags disappear, but {@code "Count: "} on a single line
     * keeps its trailing space.
     */
    static String normalizeText(String value) {
        String[] lines = value.split("\r\n|\r|\n", -1);
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            boolean isFirst = i == 0;
            boolean isLast = i == lines.length - 1;

            String line = lines[i];
            if (!isFirst) {
                line = stripLeadingSpacesAndTabs(line);
            }
            if (!isLast) {
                line = stripTrailingSpacesAndTabs(line);
            }
            if (!line.isEmpty()) {
                kept.add(line);
            }
        }
        return kept.isEmpty() ? null : String.join(" ", kept);
    }

    private static String stripLeadingSpacesAndTabs(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return s.substring(i);
    }

    private static String stripTrailingSpacesAndTabs(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
            end--;
        }
        return s.substring(0, end);
    }

    private int firstNonWhitespace(int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(src.charAt(i))) {
                return i;
            }
        }
        return start;
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * The source position the closing {@code )} of an element belongs to: its closing tag, or for a
     * self-closing element, the {@code >} of {@code />}.
     */
    private static int closingAnchor(JasxElement element) {
        if (element.closingElement() != null) {
            return element.closingElement().start();
        }
        return Math.max(element.start(), element.end() - 1);
    }

    /** {@code value} as a Java string literal, with quotes, backslashes and control characters escaped. */
    static String javaString(String value) {
        StringBuilder literal = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> literal.append("\\\"");
                case '\\' -> literal.append("\\\\");
                case '\n' -> literal.append("\\n");
                case '\r' -> literal.append("\\r");
                case '\t' -> literal.append("\\t");
                case '\b' -> literal.append("\\b");
                case '\f' -> literal.append("\\f");
                default -> {
                    boolean isControl = c < 0x20 || c == 0x7f;
                    if (isControl) {
                        literal.append(String.format("\\%03o", (int) c)); // octal escape, such as \001
                    } else {
                        literal.append(c);
                    }
                }
            }
        }
        return literal.append('"').toString();
    }

    private void error(String code, String message, int start, int end) {
        diagnostics.add(Diagnostic.error(code, message, start, end));
    }
}
