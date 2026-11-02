package jasx.lsp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jasx.compiler.ComponentIndex;
import jasx.compiler.ComponentIndex.Prop;
import jasx.compiler.ComponentIndex.PropKind;
import jasx.compiler.Schema;
import jasx.compiler.ScriptReader;
import jasx.compiler.SourceFile;
import jasx.compiler.VisibleComponents;
import jasx.parser.JasxParser;
import jasx.parser.ParseResult;
import jasx.parser.ast.Ast.ElementName;
import jasx.parser.ast.Ast.JasxAttribute;
import jasx.parser.ast.Ast.JasxElement;
import jasx.parser.ast.Ast.JasxEventName;
import jasx.parser.ast.Ast.JasxExpressionContainer;
import jasx.parser.ast.Ast.JasxFragment;
import jasx.parser.ast.Ast.JasxMarkupStatement;
import jasx.parser.ast.Ast.JasxIdentifier;
import jasx.parser.ast.Ast.JasxOpeningElement;
import jasx.parser.ast.Ast.JasxScriptBlock;
import jasx.parser.ast.Ast.JasxStatementContainer;
import jasx.parser.ast.Ast.JasxStringLiteral;
import jasx.parser.ast.Ast.JavaCode;
import jasx.parser.ast.Ast.JavaExpression;

/**
 * Completion in markup: tag names after {@code <}, and attribute names inside an opening tag.
 *
 * <p><b>Finding where the cursor is.</b> The text up to the cursor is parsed with the JASX parser. Whether a
 * {@code <} starts markup is then decided by the exact rule of grammar.md §4, so {@code i < le} in Java is
 * never taken for a tag. Because the text stops at the cursor, the markup being typed is the last node of
 * the parse, and it is unfinished:
 *
 * <ul>
 *   <li>{@code return <Ca} ends in an opening tag with no {@code >} whose name ends at the cursor:
 *       complete a <b>tag name</b>;
 *   <li>{@code <Card title="a" ti} ends in an attribute with no value whose name ends at the cursor, and
 *       {@code <Card title="a" } in whitespace inside the opening tag: complete an <b>attribute name</b>;
 *   <li>{@code <button @click="tog} ends in a browser attribute whose string isn't closed: complete the
 *       <b>browser code</b> (semantics.md §11.2), with the names the file's script declares and its refs;
 *   <li>inside another attribute string or in text: nothing; in Java (also Java inside {@code {...}}),
 *       {@link JavaCompletions} completes instead ({@link #inJava}).
 * </ul>
 *
 * <p><b>What is offered.</b> For a tag name: the components the file can use there, named as they resolve
 * ({@link VisibleComponents}), and the target's intrinsic elements. For an attribute of a component: its
 * props not given yet, required ones first. For an attribute of an intrinsic element: the attributes the
 * target's schema allows on it, and on a target with browser scripts (semantics.md §11) its {@code @} events,
 * {@code @render} and {@code ref}. In browser code: the top-level names of the file's script blocks
 * ({@link ScriptReader#topLevelNames}), and {@code $name} for each {@code ref="name"} in the file.
 */
final class Completions {
    private Completions() {}

    /** LSP CompletionItemKind values. */
    private static final int KIND_FUNCTION = 3;
    private static final int KIND_FIELD = 5;
    private static final int KIND_VARIABLE = 6;
    private static final int KIND_PROPERTY = 10;

    /**
     * One suggestion. It replaces the name being typed with {@code newText}, which is a snippet
     * ({@code $1} marks where the cursor goes) when {@code snippet} is true. A type that isn't imported yet
     * names its import in {@code importName} (a qualified name), which is added to the file with it.
     */
    record Item(String label, int kind, String detail, String documentation, String newText, boolean snippet,
            String sortText, String importName) {

        Item(String label, int kind, String detail, String documentation, String newText, boolean snippet,
                String sortText) {
            this(label, kind, detail, documentation, newText, snippet, sortText, null);
        }
    }

    /**
     * The suggestions, and the offset where the name being typed starts: they replace it up to the cursor.
     * {@code incomplete} says that typing further may bring other suggestions (types that aren't imported
     * are only offered once the name has a first letter), so the editor should ask again.
     */
    record Result(int replaceFrom, List<Item> items, boolean incomplete) {
        static final Result NONE = new Result(0, List.of());

        Result(int replaceFrom, List<Item> items) {
            this(replaceFrom, items, false);
        }
    }

    /** Where the cursor is, as far as completion is concerned. */
    private sealed interface Context {}

    /** Typing a tag name that starts at {@code nameStart}. */
    private record TagName(int nameStart) implements Context {}

    /** Typing an attribute name (from {@code nameStart}) in the opening tag of {@code element}. */
    private record AttributeName(ElementName element, int nameStart, Set<String> given) implements Context {}

    /** Typing browser code in the string of an {@code @event} attribute; the word being typed starts at {@code wordStart}. */
    private record BrowserCode(int wordStart) implements Context {}

    /**
     * The suggestions at {@code offset} in {@code file}.
     *
     * @param index  the components of the file's group and of its dependencies
     * @param schema the target's element and attribute schema
     */
    static Result at(SourceFile file, int offset, ComponentIndex index, Schema schema) {
        return at(file, offset, index, schema, false);
    }

    /**
     * The suggestions at {@code offset} in {@code file}, on a target that supports browser scripts or not
     * ({@code scripts}, semantics.md §11).
     */
    static Result at(SourceFile file, int offset, ComponentIndex index, Schema schema, boolean scripts) {
        String upToCursor = file.content().substring(0, offset);
        ParseResult parsed = JasxParser.parse(upToCursor, file.path());
        Context context = inLast(parsed.unit().segments(), upToCursor);
        return switch (context) {
            case null -> Result.NONE;
            case TagName tag -> new Result(tag.nameStart(), tagNames(file, offset, index, schema));
            case AttributeName attribute ->
                    new Result(attribute.nameStart(), attributeNames(file, offset, attribute, index, schema, scripts));
            case BrowserCode code -> scripts ? new Result(code.wordStart(), browserNames(file)) : Result.NONE;
        };
    }

    /**
     * Whether the cursor at {@code offset} is in Java code: outside markup, or in the Java of an unclosed
     * {@code {...}} in markup (an attribute value, a child, a statement container). There, {@link JavaCompletions}
     * completes; in tag names, attribute names, attribute strings and text, nothing Java does.
     */
    static boolean inJava(SourceFile file, int offset) {
        String upToCursor = file.content().substring(0, offset);
        ParseResult parsed = JasxParser.parse(upToCursor, file.path());
        return javaAtEnd(parsed.unit().segments(), upToCursor, true);
    }

    /**
     * Whether the text ends in Java, judging by the last of {@code nodes}. {@code inJavaLevel} says what the
     * nodes are part of: Java (a file, a container), where a complete element or container is followed by Java
     * again; or an element's children, where it is followed by text.
     */
    private static boolean javaAtEnd(List<?> nodes, String text, boolean inJavaLevel) {
        if (nodes.isEmpty()) {
            return inJavaLevel;
        }
        Object last = nodes.get(nodes.size() - 1);
        return switch (last) {
            case JavaCode code -> true;
            case JasxElement element -> javaAtEndOfElement(element, text, inJavaLevel);
            case JasxFragment fragment -> fragment.closingFragment() != null
                    ? inJavaLevel
                    : javaAtEnd(fragment.children(), text, false);
            case JasxExpressionContainer container -> javaAtEndOfContainer(container, text, inJavaLevel);
            case JasxStatementContainer container -> text.charAt(container.end() - 1) == '}'
                    ? inJavaLevel
                    : javaAtEnd(container.statement().segments(), text, true);
            case JasxMarkupStatement statement -> javaAtEnd(List.of(statement.markup()), text, true);
            default -> false; // text, a style block
        };
    }

    private static boolean javaAtEndOfElement(JasxElement element, String text, boolean inJavaLevel) {
        if (element.closingElement() != null) {
            return inJavaLevel;
        }
        JasxOpeningElement opening = element.openingElement();
        boolean openingTagFinished = text.charAt(opening.end() - 1) == '>';
        if (!openingTagFinished) {
            // Only the value of the last attribute can still be open: <button onclick={e -> e.
            List<JasxAttribute> attributes = opening.attributes();
            if (attributes.isEmpty()) {
                return false;
            }
            Object value = attributes.get(attributes.size() - 1).value();
            return value instanceof JasxExpressionContainer container && javaAtEndOfContainer(container, text, false);
        }
        return opening.selfClosing() ? inJavaLevel : javaAtEnd(element.children(), text, false);
    }

    private static boolean javaAtEndOfContainer(JasxExpressionContainer container, String text, boolean inJavaLevel) {
        boolean closed = text.charAt(container.end() - 1) == '}';
        if (closed) {
            return inJavaLevel;
        }
        return !(container.expression() instanceof JavaExpression java) || javaAtEnd(java.segments(), text, true);
    }

    // ---------------------------------------------------------------------------------------------
    // Where is the cursor?
    // ---------------------------------------------------------------------------------------------

    /**
     * The context in the last of {@code nodes}, the one the cursor is in or right after (the text ends at the
     * cursor). Segments of Java code, text and closed constructs give none.
     */
    private static Context inLast(List<?> nodes, String text) {
        if (nodes.isEmpty()) {
            return null;
        }
        Object last = nodes.get(nodes.size() - 1);
        return switch (last) {
            case JasxElement element -> inElement(element, text);
            case JasxFragment fragment -> fragment.closingFragment() == null ? inLast(fragment.children(), text) : null;
            case JasxExpressionContainer container -> inContainer(container, text);
            case JasxStatementContainer container -> inStatementContainer(container, text);
            case JasxMarkupStatement statement -> inLast(List.of(statement.markup()), text);
            default -> null;
        };
    }

    /** In {@code {for ...}} that isn't closed yet: markup may be typed in the statement (grammar.md §6.7). */
    private static Context inStatementContainer(JasxStatementContainer container, String text) {
        boolean closed = text.charAt(container.end() - 1) == '}';
        return closed ? null : inLast(container.statement().segments(), text);
    }

    private static Context inElement(JasxElement element, String text) {
        if (element.closingElement() != null) {
            return null; // the cursor is after a complete element
        }
        JasxOpeningElement opening = element.openingElement();
        boolean openingTagFinished = text.charAt(opening.end() - 1) == '>';
        if (!openingTagFinished) {
            return inOpeningTag(opening, text);
        }
        return opening.selfClosing() ? null : inLast(element.children(), text);
    }

    /** In {@code {...}} that isn't closed yet: markup may be typed in the Java inside it. */
    private static Context inContainer(JasxExpressionContainer container, String text) {
        boolean closed = text.charAt(container.end() - 1) == '}';
        if (closed || !(container.expression() instanceof JavaExpression java)) {
            return null;
        }
        return inLast(java.segments(), text);
    }

    private static Context inOpeningTag(JasxOpeningElement opening, String text) {
        int cursor = text.length();
        List<JasxAttribute> attributes = opening.attributes();

        if (attributes.isEmpty() && opening.name().end() == cursor) {
            return new TagName(opening.name().start()); // <Ca
        }

        Set<String> given = new HashSet<>();
        for (JasxAttribute attribute : attributes) {
            given.add(JasxParser.attributeNameText(attribute.name()));
        }

        if (!attributes.isEmpty()) {
            JasxAttribute last = attributes.get(attributes.size() - 1);
            if (inUnclosedBrowserCode(last, cursor)) {
                return new BrowserCode(wordStart(text, cursor)); // <button @click="tog
            }
            boolean typingItsName = last.value() == null && last.name().end() == cursor;
            if (typingItsName) {
                given.remove(JasxParser.attributeNameText(last.name()));
                return new AttributeName(opening.name(), last.name().start(), given); // <Card ti
            }
        }

        // After the name or a complete attribute: only whitespace may follow (<Card title="a" ).
        int endOfLast = attributes.isEmpty() ? opening.name().end() : attributes.get(attributes.size() - 1).end();
        String after = text.substring(endOfLast, cursor);
        boolean afterWhitespace = endOfLast < cursor && after.isBlank();
        if (afterWhitespace) {
            return new AttributeName(opening.name(), cursor, given);
        }
        // A lone @ isn't an attribute yet (grammar.md §6.4 needs a name after it): <button @
        boolean startingBrowserAttribute = after.endsWith("@") && after.length() > 1
                && after.substring(0, after.length() - 1).isBlank();
        return startingBrowserAttribute ? new AttributeName(opening.name(), cursor - 1, given) : null;
    }

    /** Whether the cursor is in the string of an {@code @event} attribute that isn't closed yet. */
    private static boolean inUnclosedBrowserCode(JasxAttribute attribute, int cursor) {
        if (!(attribute.name() instanceof JasxEventName) || !(attribute.value() instanceof JasxStringLiteral string)) {
            return false;
        }
        String raw = string.raw();
        boolean closed = raw.length() >= 2 && raw.endsWith("\"");
        return string.end() == cursor && !closed;
    }

    /** Where the JavaScript word ending at the cursor starts: identifier characters and {@code $}. */
    private static int wordStart(String text, int cursor) {
        int start = cursor;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    // ---------------------------------------------------------------------------------------------
    // Tag names
    // ---------------------------------------------------------------------------------------------

    private static List<Item> tagNames(SourceFile file, int offset, ComponentIndex index, Schema schema) {
        List<Item> items = new ArrayList<>();
        for (VisibleComponents.Entry entry : VisibleComponents.at(file, offset, index)) {
            ComponentIndex.Component component = entry.component();
            items.add(new Item(entry.name(), KIND_FUNCTION, component.className() + "." + component.method(),
                    Hovers.component(component), entry.name(), false, "0" + entry.name()));
        }
        for (String element : schema.tags()) {
            items.add(new Item(element, KIND_PROPERTY, "element", null, element, false, "1" + element));
        }
        return items;
    }

    // ---------------------------------------------------------------------------------------------
    // Attribute names
    // ---------------------------------------------------------------------------------------------

    private static List<Item> attributeNames(SourceFile file, int offset, AttributeName attribute,
            ComponentIndex index, Schema schema, boolean scripts) {
        String tagName = JasxParser.nameText(attribute.element());
        // Names that don't start with a lowercase letter are components (semantics.md §5).
        boolean isComponent = !Character.isLowerCase(tagName.charAt(0));
        if (isComponent) {
            ComponentIndex.Component component = VisibleComponents.named(tagName, file, offset, index);
            return component == null ? List.of() : props(component, attribute);
        }
        List<Item> items = new ArrayList<>(intrinsicAttributes(tagName, attribute, schema));
        if (scripts && schema.element(tagName) != null) {
            items.addAll(browserAttributes(tagName, attribute, schema));
        }
        return items;
    }

    /** The component's props that aren't given yet; the children parameter is not an attribute. */
    private static List<Item> props(ComponentIndex.Component component, AttributeName attribute) {
        List<Item> items = new ArrayList<>();
        for (Prop prop : component.params()) {
            if (prop.kind() == PropKind.CHILDREN || attribute.given().contains(prop.name())) {
                continue;
            }
            boolean required = prop.kind() == PropKind.REQUIRED;
            String detail = prop.type() + (required ? " (required)" : " (optional)");
            // A String is written as a string literal; anything else as a Java expression.
            String value = prop.type().equals("String") || prop.type().equals("java.lang.String") ? "\"$1\"" : "{$1}";
            items.add(new Item(prop.name(), KIND_FIELD, detail, null, prop.name() + "=" + value, true,
                    (required ? "0" : "1") + prop.name()));
        }
        return items;
    }

    /** The attributes the schema allows on the element: its own first, then global ones, then events. */
    private static List<Item> intrinsicAttributes(String tag, AttributeName attribute, Schema schema) {
        Schema.ElementSpec element = schema.element(tag);
        if (element == null) {
            return List.of(); // an unknown or custom element: the schema says nothing about its attributes
        }
        List<Item> items = new ArrayList<>();
        for (Map.Entry<String, Schema.AttributeSpec> entry : schema.attributesOf(tag).entrySet()) {
            String name = entry.getKey();
            Schema.AttributeSpec spec = entry.getValue();
            if (attribute.given().contains(name)) {
                continue;
            }
            String newText;
            String group;
            if (spec.event()) {
                newText = name + "={$1}"; // a handler is a Java expression
                group = "2";
            } else if (spec.isBoolean()) {
                newText = name; // <input disabled />
                group = element.attributes().containsKey(name) ? "0" : "1";
            } else {
                newText = name + "=\"$1\"";
                group = element.attributes().containsKey(name) ? "0" : "1";
            }
            String detail = spec.event() ? "event" : spec.isBoolean() ? "boolean attribute" : "attribute";
            items.add(new Item(name, KIND_PROPERTY, detail, null, newText, true, group + name));
        }
        return items;
    }

    // ---------------------------------------------------------------------------------------------
    // Browser code (semantics.md §11)
    // ---------------------------------------------------------------------------------------------

    /** {@code @click} for each event the schema allows on the element, {@code @render}, and {@code ref}. */
    private static List<Item> browserAttributes(String tag, AttributeName attribute, Schema schema) {
        List<Item> items = new ArrayList<>();
        for (Map.Entry<String, Schema.AttributeSpec> entry : schema.attributesOf(tag).entrySet()) {
            if (!entry.getValue().event()) {
                continue;
            }
            String name = "@" + entry.getKey().substring("on".length());
            if (!attribute.given().contains(name)) {
                items.add(new Item(name, KIND_PROPERTY, "browser code (JavaScript)", null, name + "=\"$1\"", true, "3" + name));
            }
        }
        if (!attribute.given().contains("@render")) {
            items.add(new Item("@render", KIND_PROPERTY, "browser code (JavaScript), when the element appears and its payload changes",
                    null, "@render=\"$1\"", true, "3@render"));
        }
        if (!attribute.given().contains("ref")) {
            items.add(new Item("ref", KIND_PROPERTY, "a name for browser code ($name)", null, "ref=\"$1\"", true, "1ref"));
        }
        return items;
    }

    /** The names browser code can use: the file script's top-level names, then {@code $name} for each ref. */
    private static List<Item> browserNames(SourceFile file) {
        List<?> segments = JasxParser.parse(file.content(), file.path()).unit().segments();
        List<Item> items = new ArrayList<>();
        StringBuilder script = new StringBuilder();
        for (Object segment : segments) {
            if (segment instanceof JasxScriptBlock block) {
                script.append(block.script()).append('\n');
            }
        }
        for (String name : ScriptReader.topLevelNames(script.toString()).keySet()) {
            items.add(new Item(name, KIND_FUNCTION, "from the file's script", null, name, false, "0" + name));
        }
        Set<String> refs = new LinkedHashSet<>();
        collectRefs(segments, refs);
        for (String ref : refs) {
            items.add(new Item("$" + ref, KIND_VARIABLE, "ref", null, "$" + ref, false, "1" + ref));
        }
        return items;
    }

    /** The names of every {@code ref="name"} in the nodes, at any depth (semantics.md §11.5). */
    private static void collectRefs(List<?> nodes, Set<String> refs) {
        for (Object node : nodes) {
            switch (node) {
                case JasxElement element -> {
                    for (JasxAttribute attribute : element.openingElement().attributes()) {
                        boolean isRef = attribute.name() instanceof JasxIdentifier name && name.name().equals("ref");
                        if (isRef && attribute.value() instanceof JasxStringLiteral value && !value.value().isEmpty()) {
                            refs.add(value.value());
                        } else if (attribute.value() instanceof JasxExpressionContainer container) {
                            collectRefs(List.of(container), refs);
                        }
                    }
                    collectRefs(element.children(), refs);
                }
                case JasxFragment fragment -> collectRefs(fragment.children(), refs);
                case JasxExpressionContainer container -> {
                    if (container.expression() instanceof JavaExpression java) {
                        collectRefs(java.segments(), refs);
                    }
                }
                case JasxStatementContainer container -> collectRefs(container.statement().segments(), refs);
                case JasxMarkupStatement statement -> collectRefs(List.of(statement.markup()), refs);
                default -> { }
            }
        }
    }
}
