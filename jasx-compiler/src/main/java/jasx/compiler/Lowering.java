package jasx.compiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import jasx.parser.ast.Ast.JasxEventName;
import jasx.parser.ast.Ast.JasxExpressionContainer;
import jasx.parser.ast.Ast.JasxFragment;
import jasx.parser.ast.Ast.JasxIdentifier;
import jasx.parser.ast.Ast.JasxMarkupStatement;
import jasx.parser.ast.Ast.JasxNamespacedName;
import jasx.parser.ast.Ast.JasxStatementContainer;
import jasx.parser.ast.Ast.JasxStringLiteral;
import jasx.parser.ast.Ast.JasxScriptBlock;
import jasx.parser.ast.Ast.JasxStyleBlock;
import jasx.parser.ast.Ast.JasxText;
import jasx.parser.ast.Ast.JavaCode;
import jasx.parser.ast.Ast.JavaExpression;
import jasx.parser.ast.Ast.Markup;
import jasx.parser.ast.Ast.Node;
import jasx.parser.ast.Ast.Segment;

/**
 * Lowering of one {@code .jasx} file (semantics.md §6, §7.4): Java code is copied byte for byte, and every
 * markup expression is replaced by plain Java.
 *
 * <p>Each markup expression goes through two steps:
 *
 * <ol>
 *   <li><b>Analysis.</b> The AST is turned into a {@link MarkupTree}: children are normalized (§3),
 *       components resolved and their props checked (§5), attributes checked against the target's schema and
 *       classified (§8). Every JASX error of the markup is reported here, whatever the target's lowering does.
 *   <li><b>Writing.</b> The tree is handed to the target's {@link MarkupLowering} ({@link FactoryLowering}
 *       unless the target names its own), which writes Java through an {@link Output}. The parts that are the
 *       same for every target are written here: the user's Java code, component calls (§5.6) and the
 *       statement container frame (§6.8).
 * </ol>
 *
 * <p>All output goes through an {@link Emitter}, which keeps the generated code on the same lines as the
 * markup it came from (§6.5). Lowering continues after an error, so that one run reports as many problems as
 * possible; the compiler then discards the generated file.
 */
final class Lowering {
    private static final String ELEMENT = "jasx.runtime.Element";
    private static final String OBJECT_CAST = "(java.lang.Object) ";

    // ---------------------------------------------------------------------------------------------
    // Normalized children (semantics.md §3), before they become tree nodes
    // ---------------------------------------------------------------------------------------------

    /**
     * A child after normalization: whitespace-only text is gone, text is trimmed, and empty expression
     * containers ({@code {}} and comments) are removed. It becomes a {@link MarkupTree.Child} once the
     * element's own checks are done, so that errors are reported in source order.
     */
    private sealed interface ChildValue {
        int start();
    }

    /** Normalized text. {@code start} is the first non-whitespace character, used to keep lines aligned. */
    private record TextValue(String text, int start, int end) implements ChildValue {}

    /** A {@code {expression}} child. */
    private record ExpressionValue(JasxExpressionContainer container) implements ChildValue {
        @Override
        public int start() {
            return container.start();
        }
    }

    /** A {@code {for ...}}, {@code {if ...}}, ... child: a statement whose markup statements add children. */
    private record StatementValue(JasxStatementContainer container) implements ChildValue {
        @Override
        public int start() {
            return container.start();
        }
    }

    /** A nested element or fragment. */
    private record MarkupValue(Markup markup) implements ChildValue {
        @Override
        public int start() {
            return markup.start();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // What the compiler writes itself: pieces of code and component arguments
    // ---------------------------------------------------------------------------------------------

    /** One piece of a {@link MarkupTree.Code}: copied source, generated text, or nested markup. */
    sealed interface Part {}

    /** Source text copied byte for byte. */
    record CopyPart(int start, int end) implements Part {}

    /** Generated text for the source position {@code anchor}. */
    record WritePart(String text, int anchor) implements Part {}

    /** A markup expression inside the code, lowered by the target. */
    record MarkupPart(MarkupTree.Markup markup) implements Part {}

    /** One argument of a component call (semantics.md §5.6), in parameter order. */
    sealed interface Argument {}

    /** A given prop. */
    record ValueArgument(MarkupTree.Value value) implements Argument {}

    /** The literal of an absent default prop, or the value of an absent optional one. */
    record TextArgument(String text, int anchor) implements Argument {}

    /** The one child of a component that takes exactly one; null (written as null) if the count is wrong. */
    record SingleChildArgument(MarkupTree.Child child, int anchor) implements Argument {}

    /** The children of a component that takes a list: {@code java.util.List.<Element>of(...)}. */
    record ChildListArgument(List<MarkupTree.Child> children, int start, int closeAnchor) implements Argument {}

    // ---------------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------------

    private final SourceFile file;
    private final String src;
    private final Target target;
    private final Schema schema;
    private final Resolver resolver;
    private final Emitter emitter;
    private final MarkupLowering lowering;
    private final Output out = new Output();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    /** The file's style sheet, or null. If set, every intrinsic element is wrapped (semantics.md §10.5). */
    private StyleBlocks.Plan styles;
    /** The component tags resolved so far, for editor tooling (CompilationResult.uses). */
    private final List<CompilationResult.Use> uses = new ArrayList<>();
    /** The props given to those components, for editor tooling (CompilationResult.props). */
    private final List<CompilationResult.PropUse> props = new ArrayList<>();
    /** How many statement containers were analysed: each gets its own list variable (semantics.md §6.8). */
    private int statementContainers;

    // Browser scripts (semantics.md §11)
    /** The file's script: its script blocks, joined; empty if it has none. */
    private final StringBuilder script = new StringBuilder();
    /** The script's top-level names, each with whether the script exports it already (§11.1). */
    private Map<String, Boolean> scriptNames = Map.of();
    /** The refs the file's `ref` attributes declare (§11.5). */
    private final Set<String> declaredRefs = new HashSet<>();
    /** The top-level names that calls use, which the module exports (§11.6). */
    private final Set<String> calledFunctions = new LinkedHashSet<>();
    /** The functions of the file's inline snippets, in source order (§11.6). */
    private final List<String> inlineFunctions = new ArrayList<>();
    /** Whether the file has a script block or a browser attribute, and so a module. */
    private boolean hasModule;
    /**
     * The codes and markup nodes of the markup expression being lowered, each of which the target must
     * write exactly once (see {@link Output}).
     */
    private final List<Object> toWrite = new ArrayList<>();

    Lowering(SourceFile file, LineMap lines, Target target, Resolver resolver) {
        this.file = file;
        this.src = file.content();
        this.target = target;
        this.schema = target.schema();
        this.resolver = resolver;
        this.emitter = new Emitter(src, lines);
        this.lowering = target.newLowering();
    }

    List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    List<CompilationResult.Use> uses() {
        return uses;
    }

    List<CompilationResult.PropUse> props() {
        return props;
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
        prepareScripts(unit);
        List<Insertion> pending = new ArrayList<>(insertions);
        pending.sort(Comparator.comparingInt(Insertion::offset).thenComparingInt(Insertion::order));
        int next = 0;

        for (Segment segment : unit.segments()) {
            // Insertions where markup or a style block starts (such as the style sheet field right after
            // "class T {" in "class T {<style>...") belong before it.
            boolean isJava = segment instanceof JavaCode;
            while (!isJava && next < pending.size() && pending.get(next).offset() <= segment.start()) {
                emitter.emit(pending.get(next).text(), pending.get(next).offset());
                next++;
            }

            if (segment instanceof Markup markup) {
                lowerMarkupExpression(markup);
                continue;
            }
            if (segment instanceof JasxStyleBlock) {
                continue; // removed; its CSS is in the style sheet field (semantics.md §10.5)
            }
            if (segment instanceof JasxScriptBlock) {
                continue; // removed; its JavaScript is in the file's script module (semantics.md §11.1)
            }

            // A Java segment: copy it, writing each insertion that falls inside it at its place.
            int copiedUpTo = segment.start();
            while (next < pending.size() && pending.get(next).offset() < segment.end()) {
                Insertion insertion = pending.get(next);
                next++;
                if (insertion.offset() < copiedUpTo) {
                    continue; // inside markup that came before this segment: can't happen for valid input
                }
                emitter.copy(copiedUpTo, insertion.offset());
                emitter.emit(insertion.text(), insertion.offset());
                copiedUpTo = insertion.offset();
            }
            emitter.copy(copiedUpTo, segment.end());
        }

        // Insertions at the very end of the file, such as the file class's closing brace.
        for (; next < pending.size(); next++) {
            emitter.emit(pending.get(next).text(), pending.get(next).offset());
        }

        return new GeneratedFile(file, file.javaPath(), emitter.text(), emitter.spans());
    }

    /** Analyses one top-level markup expression, then has it written, and checks that nothing was left out. */
    private void lowerMarkupExpression(Markup markup) {
        toWrite.clear();
        MarkupTree.Markup tree = analyse(markup);
        out.markup(tree);
        out.checkEverythingWritten();
    }

    // =============================================================================================
    // Analysis: from the AST to the MarkupTree
    // =============================================================================================

    /** An element or a fragment. */
    private MarkupTree.Markup analyse(Markup markup) {
        MarkupTree.Markup node = switch (markup) {
            case JasxFragment fragment -> fragment(fragment);
            case JasxElement element -> element(element);
        };
        toWrite.add(node);
        return node;
    }

    private MarkupTree.Fragment fragment(JasxFragment fragment) {
        List<MarkupTree.Child> children = children(normalize(fragment.children()));
        int closeAnchor = fragment.closingFragment() != null ? fragment.closingFragment().start() : fragment.end();
        return new MarkupTree.Fragment(children, fragment.start(), fragment.end(), closeAnchor);
    }

    /**
     * An element: intrinsic ({@code <div>}) or component ({@code <Card>}). A {@code key} attribute makes it a
     * {@link MarkupTree.Keyed} and is not passed on (semantics.md §4.1, §6.4).
     */
    private MarkupTree.Markup element(JasxElement element) {
        JasxAttribute key = null;
        List<JasxAttribute> attributes = new ArrayList<>();
        for (JasxAttribute attribute : element.openingElement().attributes()) {
            if (isKey(attribute)) {
                key = attribute;
            } else {
                attributes.add(attribute);
            }
        }

        MarkupTree.Value keyValue = key != null ? keyValue(key) : null;
        MarkupTree.Markup unkeyed = isIntrinsic(element.openingElement().name())
                ? intrinsic(element, attributes)
                : component(element, attributes);
        if (keyValue == null) {
            return unkeyed;
        }
        toWrite.add(unkeyed); // written by the target's keyed(...), like any nested markup
        return new MarkupTree.Keyed(keyValue, unkeyed, element.start(), element.end(), closingAnchor(element));
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
    private MarkupTree.Value keyValue(JasxAttribute key) {
        if (key.value() instanceof JasxStringLiteral string) {
            return new MarkupTree.Literal(javaString(string.value()), string.value(), string.start());
        }
        if (key.value() instanceof JasxExpressionContainer container
                && container.expression() instanceof JavaExpression expression) {
            return new MarkupTree.Expression(code(expression), container.start(), container.end());
        }
        error("JASX2009", "a key must be a string literal or an expression", key.start(), key.end());
        return new MarkupTree.Literal("null", null, key.start());
    }

    // ---------------------------------------------------------------------------------------------
    // Intrinsic elements (semantics.md §6.3)
    // ---------------------------------------------------------------------------------------------

    /** An intrinsic element. The tag and every attribute are checked against the target's schema. */
    private MarkupTree.Intrinsic intrinsic(JasxElement element, List<JasxAttribute> attributes) {
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
            error("JASX2102", "<" + tag + "> is a void element and cannot have children", first.start(),
                    childEnd(first));
        }

        // Unknown and custom elements use the generic builder, which only has .attr(name, value).
        boolean unknownElement = spec == null;
        String builderClass = target.attributesPackage() + "." + (unknownElement ? "Custom" : Schema.pascalCase(tag));
        List<MarkupTree.Attribute> attributeNodes = new ArrayList<>();
        for (JasxAttribute attribute : attributes) {
            attributeNodes.add(attribute(tag, unknownElement, attribute));
        }

        // In a file with a style sheet, the element uses it (§10.5), with the scope class if one of the
        // file's scoped compounds may match it.
        String styleSheet = styles != null ? styles.fieldReference() : null;
        boolean scoped = styles != null && mayBeStyled(tag, attributes);

        return new MarkupTree.Intrinsic(tag, builderClass, attributeNodes, children(children), styleSheet, scoped,
                element.start(), element.end(), closingAnchor(element));
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
     * One attribute: its builder method ({@code className}), or {@code attr} for names the schema accepts by
     * pattern (data-*, aria-*, ...), namespaced names, and custom elements; and its kind (semantics.md §8).
     */
    private MarkupTree.Attribute attribute(String tag, boolean unknownElement, JasxAttribute attribute) {
        String name = JasxParser.attributeNameText(attribute.name());
        if (attribute.name() instanceof JasxEventName) {
            return browserAttribute(name, attribute);
        }
        if (name.equals("ref") && attribute.name() instanceof JasxIdentifier) {
            return refAttribute(attribute);
        }

        boolean byName = unknownElement
                || attribute.name() instanceof JasxNamespacedName
                || schema.matchesPattern(name);
        String method = "attr";
        boolean isEvent = false;

        if (!byName) {
            Schema.AttributeSpec spec = schema.attribute(tag, name);
            if (spec == null) {
                error("JASX2101", "<" + tag + "> has no attribute '" + name + "' on target '" + target.id() + "'",
                        attribute.start(), attribute.end());
                byName = true; // keep going, so that later errors are found too
            } else {
                method = spec.method();
                isEvent = spec.event();
                checkEventHandler(name, spec, attribute);
            }
        }

        boolean isExpression = attribute.value() instanceof JasxExpressionContainer;
        MarkupTree.AttributeKind kind = !isExpression ? MarkupTree.AttributeKind.STATIC
                : isEvent ? MarkupTree.AttributeKind.HANDLER
                : MarkupTree.AttributeKind.DYNAMIC;
        return new MarkupTree.Attribute(name, method, byName, kind, value(attribute), attribute.start(),
                attribute.end());
    }

    /**
     * On a target without Java event handlers, an event attribute only takes a string (JASX2103). On a target with
     * browser scripts, it takes no string: browser code is a browser attribute (JASX2108, semantics.md §11.8).
     */
    private void checkEventHandler(String name, Schema.AttributeSpec spec, JasxAttribute attribute) {
        if (spec.event() && target.scripts() && attribute.value() instanceof JasxStringLiteral) {
            error("JASX2108", "on target '" + target.id() + "', '" + name + "' takes a Java handler; write browser"
                    + " code as @" + name.substring(2) + "=\"...\"", attribute.start(), attribute.end());
        }
        boolean javaHandler = attribute.value() instanceof JasxExpressionContainer;
        if (spec.event() && javaHandler && !target.javaEventHandlers()) {
            error("JASX2103", "event attribute '" + name + "' accepts only a string on target '" + target.id()
                            + "', which has no Java event handlers",
                    attribute.value().start(), attribute.value().end());
        }
    }

    /**
     * The value of an attribute or prop (semantics.md §4): {@code true} for a shorthand attribute, a string
     * literal, an expression, or markup.
     */
    private MarkupTree.Value value(JasxAttribute attribute) {
        return switch (attribute.value()) {
            case null -> new MarkupTree.Literal("true", null, attribute.start());
            case JasxStringLiteral string -> new MarkupTree.Literal(javaString(string.value()), string.value(),
                    string.start());
            case JasxExpressionContainer container -> {
                if (container.expression() instanceof JavaExpression expression) {
                    yield new MarkupTree.Expression(code(expression), container.start(), container.end());
                }
                yield new MarkupTree.Literal("null", null, container.start()); // {} is a syntax error (JASX1008)
            }
            case Markup markup -> new MarkupTree.MarkupValue(analyse(markup));
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Components (semantics.md §5.4–§5.6)
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code <Card subtitle="s" title="t" />}  →  a call {@code app.Page.Card("t", "s")}.
     *
     * <p>Arguments are in <em>parameter</em> order, whatever the attribute order; absent optional props get
     * {@code null} (or a primitive's default), absent default props get their literal, and the
     * {@code children} parameter gets the child content.
     */
    private MarkupTree.ComponentCall component(JasxElement element, List<JasxAttribute> attributes) {
        int closeAnchor = closingAnchor(element);
        Resolver.Resolution resolution = resolver.resolve(element.openingElement().name(), element.start());
        if (resolution.error() != null) {
            diagnostics.add(resolution.error());
            return unresolved(element, closeAnchor);
        }
        Component component = resolution.component();
        recordUse(component, element);
        String tag = "<" + JasxParser.nameText(element.openingElement().name()) + ">";

        Map<String, JasxAttribute> givenProps = checkProps(tag, component, attributes);
        givenProps.forEach((name, attribute) -> props.add(new CompilationResult.PropUse(
                component, name, file.path(), attribute.name().start(), attribute.name().end())));
        List<ChildValue> children = normalize(element.children());
        checkChildren(tag, component, children, element);
        checkRequiredProps(tag, component, givenProps, element);

        List<Argument> arguments = new ArrayList<>();
        for (Prop param : component.params()) {
            arguments.add(argument(param, givenProps, component.children(), children, element));
        }
        return new MarkupTree.ComponentCall(component.className(), component.method(), arguments, element.start(),
                element.end(), closeAnchor);
    }

    /** A component that couldn't be resolved: written as {@code null}; the file has an error anyway. */
    private static MarkupTree.ComponentCall unresolved(JasxElement element, int closeAnchor) {
        return new MarkupTree.ComponentCall(null, null, List.of(), element.start(), element.end(), closeAnchor);
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
            boolean browserOnly = attribute.name() instanceof JasxEventName || (name.equals("ref") && target.scripts());

            if (browserOnly) {
                error("JASX2018", "'" + name + "' belongs on an element, not on the component " + tag
                        + " (semantics.md §11.2)", attribute.start(), attribute.end());
            } else if (name.equals("children") && component.children() != Children.NONE) {
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
            error("JASX2003", tag + " takes no children", first.start(), childEnd(first));
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
    private Argument argument(Prop prop, Map<String, JasxAttribute> givenProps, Children childrenKind,
            List<ChildValue> children, JasxElement element) {
        if (prop.kind() == PropKind.CHILDREN) {
            if (childrenKind == Children.SINGLE) {
                // A wrong count was already reported (JASX2004); the argument is then null.
                MarkupTree.Child only = children.size() == 1 ? children(children).get(0) : null;
                return new SingleChildArgument(only, element.start());
            }
            return new ChildListArgument(children(children), element.start(), closingAnchor(element));
        }
        JasxAttribute given = givenProps.get(prop.name());
        if (given != null) {
            return new ValueArgument(value(given));
        }
        if (prop.kind() == PropKind.DEFAULT) {
            return new TextArgument(prop.defaultValue(), element.start());
        }
        return new TextArgument(absentValue(prop.type()), element.start());
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
    // Children and code
    // ---------------------------------------------------------------------------------------------

    /** Turns normalized children into tree nodes, analysing the markup and code inside them. */
    private List<MarkupTree.Child> children(List<ChildValue> values) {
        List<MarkupTree.Child> children = new ArrayList<>();
        for (ChildValue value : values) {
            children.add(switch (value) {
                case TextValue t -> new MarkupTree.Text(t.text(), t.start(), t.end());
                case ExpressionValue v -> new MarkupTree.Dynamic(
                        code((JavaExpression) v.container().expression()), v.container().start(), v.container().end());
                case StatementValue s -> new MarkupTree.Statement(
                        statementContainer(s.container()), s.container().start(), s.container().end());
                case MarkupValue m -> analyse(m.markup());
            });
        }
        return children;
    }

    /** A Java expression: its source, with any markup nested inside it lowered by the target. */
    private MarkupTree.Code code(JavaExpression expression) {
        List<Part> parts = new ArrayList<>();
        for (Segment segment : expression.segments()) {
            if (segment instanceof Markup markup) {
                parts.add(new MarkupPart(analyse(markup)));
            } else {
                parts.add(new CopyPart(segment.start(), segment.end()));
            }
        }
        return newCode(parts, expression.start(), expression.end());
    }

    /**
     * A statement container (semantics.md §6.8), as a switch expression whose block runs the statement and
     * yields the list its markup statements filled:
     *
     * <pre>
     *   {for (T t : ts) { &lt;li&gt;{t}&lt;/li&gt; }}
     *   →  switch (0) { default -&gt; { java.util.ArrayList&lt;java.lang.Object&gt; jasx$children0 =
     *          new java.util.ArrayList&lt;&gt;(); for (T t : ts) { jasx$children0.add((java.lang.Object) (F.h("li", ...))); }
     *          yield jasx$children0; } }
     * </pre>
     *
     * <p>A switch expression rather than a lambda, so that the statement can assign local variables of the
     * enclosing method. Each container of the file gets its own list name, so that nested ones don't clash.
     * The {@code (java.lang.Object)} cast of a child (§6.7) is the target's to write.
     */
    private MarkupTree.Code statementContainer(JasxStatementContainer container) {
        String list = "jasx$children" + statementContainers++;
        List<Part> parts = new ArrayList<>();
        parts.add(new WritePart("switch (0) { default -> { java.util.ArrayList<java.lang.Object> " + list
                + " = new java.util.ArrayList<>(); ", container.start()));
        for (Segment segment : container.statement().segments()) {
            switch (segment) {
                case JasxMarkupStatement statement -> {
                    parts.add(new WritePart(list + ".add(" + OBJECT_CAST + "(", statement.start()));
                    parts.add(new MarkupPart(analyse(statement.markup())));
                    parts.add(new WritePart("));", statement.end() - 1));
                }
                case Markup markup -> parts.add(new MarkupPart(analyse(markup))); // as anywhere in Java code
                case JasxStyleBlock block -> {} // removed; its CSS is in the style sheet (§10.5)
                default -> parts.add(new CopyPart(segment.start(), segment.end()));
            }
        }
        parts.add(new WritePart(" yield " + list + "; } }", container.end() - 1));
        return newCode(parts, container.start(), container.end());
    }

    private MarkupTree.Code newCode(List<Part> parts, int start, int end) {
        MarkupTree.Code code = new MarkupTree.Code(List.copyOf(parts), start, end);
        toWrite.add(code);
        return code;
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
                case JasxStatementContainer container -> values.add(new StatementValue(container));
                case Markup markup -> values.add(new MarkupValue(markup));
                case JasxStyleBlock block -> {} // not a child value: its CSS is in the style sheet (§3.3)
            }
        }
        return values;
    }

    /** Where a normalized child ends in the source, for error ranges. */
    private static int childEnd(ChildValue child) {
        return switch (child) {
            case TextValue t -> t.end();
            case ExpressionValue v -> v.container().end();
            case StatementValue s -> s.container().end();
            case MarkupValue m -> m.markup().end();
        };
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

    // =============================================================================================
    // Writing: the output the target's lowering writes to
    // =============================================================================================

    /**
     * The {@link LoweringOutput} of this file. It writes through the {@link Emitter}, calls the target's
     * lowering for nested markup, writes component calls itself, and checks that the lowering writes every
     * piece of the tree exactly once: a piece left out would silently drop the user's code, and one written
     * twice would run it twice.
     */
    private final class Output implements LoweringOutput {
        private final Set<Object> written = Collections.newSetFromMap(new IdentityHashMap<>());

        @Override
        public void write(String java, int anchor) {
            if (java.indexOf('\n') >= 0 || java.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(problem("wrote a line break, which would shift the lines of the "
                        + "generated file (semantics.md §6.5): " + java.strip()));
            }
            emitter.emit(java, anchor);
        }

        @Override
        public void code(MarkupTree.Code code) {
            markWritten(code, code.start());
            for (Part part : code.parts) {
                switch (part) {
                    case CopyPart copy -> emitter.copy(copy.start(), copy.end());
                    case WritePart text -> emitter.emit(text.text(), text.anchor());
                    case MarkupPart nested -> markup(nested.markup());
                }
            }
        }

        @Override
        public void markup(MarkupTree.Markup markup) {
            markWritten(markup, markup.start());
            switch (markup) {
                case MarkupTree.Intrinsic element -> lowering.intrinsic(element, this);
                case MarkupTree.Fragment fragment -> lowering.fragment(fragment, this);
                case MarkupTree.Keyed keyed -> lowering.keyed(keyed, this);
                case MarkupTree.ComponentCall call -> {
                    lowering.component(call, this);
                    if (!callsWritten.remove(call)) {
                        throw new IllegalStateException(problem("did not write the call of the component at "
                                + where(call.start())));
                    }
                }
            }
        }

        /** Calls written by {@link #call}, until {@link #markup} checks them off. */
        private final Set<MarkupTree.ComponentCall> callsWritten = new HashSet<>();

        @Override
        public void call(MarkupTree.ComponentCall call) {
            if (!callsWritten.add(call)) {
                throw new IllegalStateException(problem("wrote the call of the component at " + where(call.start())
                        + " twice"));
            }
            componentCall(call);
        }

        @Override
        public void value(MarkupTree.Value value) {
            switch (value) {
                case MarkupTree.Literal literal -> write(literal.java(), literal.anchor());
                case MarkupTree.Expression expression -> {
                    write("(", expression.start());
                    code(expression.expression());
                    write(")", expression.end() - 1);
                }
                case MarkupTree.MarkupValue markup -> markup(markup.markup());
            }
        }

        /** {@code app.Page.Card("t", F.text("child"))}: a direct call, arguments in parameter order (§5.6). */
        private void componentCall(MarkupTree.ComponentCall call) {
            if (call.className() == null) {
                write("null", call.start()); // unresolved, already reported
                return;
            }
            write(call.className() + "." + call.method() + "(", call.start());
            for (int i = 0; i < call.arguments.size(); i++) {
                if (i > 0) {
                    write(", ", call.start());
                }
                argument(call.arguments.get(i));
            }
            write(")", call.closeAnchor);
        }

        private void argument(Argument argument) {
            switch (argument) {
                case ValueArgument given -> value(given.value());
                case TextArgument text -> write(text.text(), text.anchor());
                case SingleChildArgument single -> {
                    if (single.child() == null) {
                        write("null", single.anchor());
                    } else {
                        lowering.componentChild(single.child(), this);
                    }
                }
                case ChildListArgument list -> {
                    write("java.util.List.<" + ELEMENT + ">of(", list.start());
                    for (int i = 0; i < list.children().size(); i++) {
                        MarkupTree.Child child = list.children().get(i);
                        if (i > 0) {
                            write(", ", child.start());
                        }
                        lowering.componentChild(child, this);
                    }
                    write(")", list.closeAnchor());
                }
            }
        }

        private void markWritten(Object piece, int position) {
            if (!written.add(piece)) {
                throw new IllegalStateException(problem("wrote the code at " + where(position) + " twice"));
            }
        }

        /** After a markup expression: every piece of its tree must have been written. */
        void checkEverythingWritten() {
            for (Object piece : toWrite) {
                if (!written.contains(piece)) {
                    int position = piece instanceof MarkupTree.Code code ? code.start() : ((MarkupTree.Markup) piece).start();
                    throw new IllegalStateException(problem("did not write the code at " + where(position)));
                }
            }
            written.clear();
        }

        private String problem(String what) {
            return "the lowering of target '" + target.id() + "' (" + lowering.getClass().getName() + ") " + what;
        }

        private String where(int position) {
            return file.path() + ":" + (lineOf(position));
        }

        private int lineOf(int position) {
            int line = 1;
            for (int i = 0; i < position && i < src.length(); i++) {
                if (src.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }
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

    // ---------------------------------------------------------------------------------------------
    // Browser scripts (semantics.md §11)
    // ---------------------------------------------------------------------------------------------

    /** Reads the file's script blocks and `ref` attributes before its markup is lowered (§11.1, §11.5). */
    private void prepareScripts(JasxCompilationUnit unit) {
        for (Segment segment : unit.segments()) {
            if (segment instanceof JasxScriptBlock block) {
                if (!target.scripts()) {
                    error("JASX2107", "target '" + target.id() + "' has no script blocks (semantics.md §11.8)",
                            block.start(), block.start() + "<script>".length());
                    continue;
                }
                if (hasModule) {
                    script.append('\n');
                }
                script.append(block.script());
                hasModule = true;
            }
        }
        scriptNames = ScriptReader.topLevelNames(script.toString());
        collectRefs(unit);
    }

    /** Adds every name a `ref` attribute with a string literal declares, anywhere under {@code node}. */
    private void collectRefs(Node node) {
        switch (node) {
            case JasxCompilationUnit unit -> unit.segments().forEach(this::collectRefs);
            case JavaExpression expression -> expression.segments().forEach(this::collectRefs);
            case JasxMarkupStatement statement -> collectRefs(statement.markup());
            case JasxStatementContainer container -> container.statement().segments().forEach(this::collectRefs);
            case JasxExpressionContainer container -> collectRefs(container.expression());
            case JasxFragment fragment -> fragment.children().forEach(this::collectRefs);
            case JasxElement element -> {
                for (JasxAttribute attribute : element.openingElement().attributes()) {
                    boolean isRef = attribute.name() instanceof JasxIdentifier id && id.name().equals("ref");
                    if (isRef && attribute.value() instanceof JasxStringLiteral literal) {
                        declaredRefs.add(literal.value());
                    }
                    if (attribute.value() != null) {
                        collectRefs(attribute.value());
                    }
                }
                element.children().forEach(this::collectRefs);
            }
            default -> {} // Java code, text, style and script blocks
        }
    }

    /** The module of the file (§11.6), or null if it has no script block and no browser attribute. */
    CompilationResult.ScriptModule module() {
        if (!hasModule) {
            return null;
        }
        StringBuilder content = new StringBuilder(script);
        List<String> toExport = new ArrayList<>();
        for (String name : calledFunctions) {
            if (!scriptNames.getOrDefault(name, true)) {
                toExport.add(name);
            }
        }
        if (!toExport.isEmpty()) {
            content.append("\nexport { ").append(String.join(", ", toExport)).append(" };");
        }
        for (String function : inlineFunctions) {
            content.append('\n').append(function);
        }
        return new CompilationResult.ScriptModule(moduleName(), content.append('\n').toString());
    }

    /** {@code web/Menu.jasx}: {@code web/Menu.jasx.js}. */
    private String moduleName() {
        return file.path() + ".js";
    }

    /** {@code @click="..."} (§11.2): a dynamic attribute {@code .attr("@click", F.script(...))}, by name (§11.6). */
    private MarkupTree.Attribute browserAttribute(String name, JasxAttribute attribute) {
        if (!target.scripts()) {
            error("JASX2107", "target '" + target.id() + "' has no browser attributes (semantics.md §11.8)",
                    attribute.start(), attribute.end());
            return placeholder(name, attribute);
        }
        if (!(attribute.value() instanceof JasxStringLiteral literal)) {
            error("JASX2017", "'" + name + "' takes JavaScript in a string: " + name + "=\"...\"",
                    attribute.start(), attribute.end());
            return placeholder(name, attribute);
        }
        hasModule = true;
        SourcePlaces places = new SourcePlaces(literal, attribute);
        String factory = target.factory();
        List<Part> parts = new ArrayList<>();
        switch (BrowserAttribute.parse(literal.value())) {
            case BrowserAttribute.Problem problem -> {
                error("JASX2020", "the payload of '" + name + "': " + problem.message(), places.at(problem.start()),
                        places.at(problem.end()));
                return placeholder(name, attribute);
            }
            case BrowserAttribute.Call call -> {
                if (!scriptNames.containsKey(call.function())) {
                    error("JASX2019", "'" + call.function() + "' is not a function of this file's script; import"
                                    + " or declare it in a <script> block, or write window." + call.function()
                                    + " for a browser global", places.at(call.functionStart()),
                            places.at(call.functionEnd()));
                    return placeholder(name, attribute);
                }
                calledFunctions.add(call.function());
                parts.add(new WritePart(factory + ".script(" + javaString(moduleName()) + ", "
                        + javaString(call.function()) + ", ", attribute.start()));
                if (call.payload() == null) {
                    parts.add(new WritePart(OBJECT_CAST + "null", attribute.start()));
                } else if (!payload(call.payload(), places, factory, parts)) {
                    return placeholder(name, attribute);
                }
                parts.add(new WritePart(")", attribute.end() - 1));
            }
            case BrowserAttribute.Inline inline -> {
                List<String> refs = new ArrayList<>();
                for (ScriptReader.RefUse use : inline.refs()) {
                    if (!checkRef(use.name(), places.at(use.offset()), places.at(use.offset() + 1 + use.name().length()))) {
                        return placeholder(name, attribute);
                    }
                    if (!refs.contains(use.name())) {
                        refs.add(use.name());
                    }
                }
                String function = "jx$" + (inlineFunctions.size() + 1);
                String parameters = refs.isEmpty() ? "event"
                        : "event, { $" + String.join(", $", refs) + " }";
                inlineFunctions.add("export function " + function + "(" + parameters + ") {\n" + inline.code() + "\n}");
                StringBuilder call = new StringBuilder(factory + ".script(" + javaString(moduleName()) + ", "
                        + javaString(function) + ", ");
                if (refs.isEmpty()) {
                    call.append(OBJECT_CAST).append("null");
                } else {
                    call.append(factory).append(".object(");
                    for (int i = 0; i < refs.size(); i++) {
                        call.append(i > 0 ? ", " : "").append(javaString("$" + refs.get(i))).append(", ")
                                .append(OBJECT_CAST).append(factory).append(".ref(").append(javaString(refs.get(i))).append(")");
                    }
                    call.append(")");
                }
                parts.add(new WritePart(call.append(")").toString(), attribute.start()));
            }
        }
        MarkupTree.Code code = newCode(parts, attribute.start(), attribute.end());
        return new MarkupTree.Attribute(name, "attr", true, MarkupTree.AttributeKind.DYNAMIC,
                new MarkupTree.Expression(code, attribute.start(), attribute.end()), attribute.start(), attribute.end());
    }

    /**
     * Writes a payload value (§11.6): objects, lists and refs with the factory's functions, literals as Java
     * literals, and Java names as their source text. Returns false after reporting an unknown ref.
     */
    private boolean payload(BrowserAttribute.Value value, SourcePlaces places, String factory, List<Part> parts) {
        int anchor = places.anchor();
        switch (value) {
            case BrowserAttribute.Literal literal -> parts.add(new WritePart(OBJECT_CAST + literal.java(), anchor));
            case BrowserAttribute.Ref ref -> {
                if (!checkRef(ref.name(), places.at(ref.start()), places.at(ref.end()))) {
                    return false;
                }
                parts.add(new WritePart(OBJECT_CAST + factory + ".ref(" + javaString(ref.name()) + ")", anchor));
            }
            case BrowserAttribute.JavaName name -> {
                parts.add(new WritePart(OBJECT_CAST + "(", anchor));
                if (places.exact()) {
                    parts.add(new CopyPart(places.at(name.start()), places.at(name.end())));
                } else {
                    parts.add(new WritePart(name.text(), anchor));
                }
                parts.add(new WritePart(")", anchor));
            }
            case BrowserAttribute.ObjectValue object -> {
                parts.add(new WritePart(OBJECT_CAST + factory + ".object(", anchor));
                for (int i = 0; i < object.keys().size(); i++) {
                    parts.add(new WritePart((i > 0 ? ", " : "") + javaString(object.keys().get(i)) + ", ", anchor));
                    if (!payload(object.values().get(i), places, factory, parts)) {
                        return false;
                    }
                }
                parts.add(new WritePart(")", anchor));
            }
            case BrowserAttribute.ListValue list -> {
                parts.add(new WritePart(OBJECT_CAST + factory + ".list(", anchor));
                for (int i = 0; i < list.values().size(); i++) {
                    if (i > 0) {
                        parts.add(new WritePart(", ", anchor));
                    }
                    if (!payload(list.values().get(i), places, factory, parts)) {
                        return false;
                    }
                }
                parts.add(new WritePart(")", anchor));
            }
        }
        return true;
    }

    /** A ref must be declared by a `ref` attribute of the file (JASX2021). */
    private boolean checkRef(String name, int start, int end) {
        if (declaredRefs.contains(name)) {
            return true;
        }
        error("JASX2021", "no element of this file has ref=\"" + name + "\"", start, end);
        return false;
    }

    /** {@code ref="menu"} (§11.5): a static attribute by name. */
    private MarkupTree.Attribute refAttribute(JasxAttribute attribute) {
        if (!target.scripts()) {
            error("JASX2107", "target '" + target.id() + "' has no refs (semantics.md §11.8)", attribute.start(),
                    attribute.end());
            return placeholder("ref", attribute);
        }
        boolean isName = attribute.value() instanceof JasxStringLiteral literal && isJavaName(literal.value());
        if (!isName) {
            error("JASX2022", "a ref is a name in a string: ref=\"menu\"", attribute.start(), attribute.end());
            return placeholder("ref", attribute);
        }
        return new MarkupTree.Attribute("ref", "attr", true, MarkupTree.AttributeKind.STATIC, value(attribute),
                attribute.start(), attribute.end());
    }

    private static boolean isJavaName(String text) {
        if (text.isEmpty() || !Character.isJavaIdentifierStart(text.charAt(0))) {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            if (!Character.isJavaIdentifierPart(text.charAt(i))) {
                return false;
            }
        }
        return !text.equals("true") && !text.equals("false") && !text.equals("null");
    }

    /** An attribute written as null after an error: the file has an error and isn't lowered anyway. */
    private static MarkupTree.Attribute placeholder(String name, JasxAttribute attribute) {
        return new MarkupTree.Attribute(name, "attr", true, MarkupTree.AttributeKind.STATIC,
                new MarkupTree.Literal("null", null, attribute.start()), attribute.start(), attribute.end());
    }

    /**
     * Where the characters of an attribute string are in the source. Offsets map exactly when the string has no
     * escape or character reference (its value is its raw text); otherwise everything maps to the attribute.
     */
    private record SourcePlaces(int base, int fallback, boolean exact) {
        SourcePlaces(JasxStringLiteral literal, JasxAttribute attribute) {
            this(literal.start() + 1, attribute.start(),
                    literal.raw().substring(1, literal.raw().length() - 1).equals(literal.value()));
        }

        int at(int offset) {
            return exact ? base + offset : fallback;
        }

        int anchor() {
            return fallback;
        }
    }

    private void error(String code, String message, int start, int end) {
        diagnostics.add(Diagnostic.error(code, message, start, end));
    }
}
