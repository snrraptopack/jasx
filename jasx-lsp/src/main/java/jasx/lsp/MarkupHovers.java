package jasx.lsp;

import jasx.compiler.ComponentIndex;
import jasx.compiler.Schema;
import jasx.parser.JasxParser;
import jasx.parser.ast.Ast.AttributeValue;
import jasx.parser.ast.Ast.Child;
import jasx.parser.ast.Ast.JasxAttribute;
import jasx.parser.ast.Ast.JasxElement;
import jasx.parser.ast.Ast.JasxExpressionContainer;
import jasx.parser.ast.Ast.JasxFragment;
import jasx.parser.ast.Ast.JasxMarkupStatement;
import jasx.parser.ast.Ast.JasxStatementContainer;
import jasx.parser.ast.Ast.JavaExpression;
import jasx.parser.ast.Ast.Markup;
import jasx.parser.ast.Ast.Node;
import jasx.parser.ast.Ast.Segment;
import java.util.List;

/**
 * Hover on markup that isn't a component tag (those are {@link Hovers#component}): an intrinsic element's
 * tag name, an attribute of an intrinsic element, and a prop given to a component.
 *
 * <ul>
 *   <li>{@code <div>}: that it is an element of the target, whether it is void, and a link to its MDN page;
 *   <li>{@code class="..."} on an intrinsic element: whether it is a global, boolean or event attribute;
 *   <li>{@code title="..."} on a component: the prop's type and how it is passed (required, optional, its
 *       default), from the component index.
 * </ul>
 *
 * <p>The file is parsed again (it is cheap) to find the markup under the cursor; the component a tag names
 * comes from the analysis ({@code CompilationResult.uses}), passed in as a lookup.
 */
final class MarkupHovers {
    private MarkupHovers() {}

    /** The component a tag name at a given offset resolved to, or null (an intrinsic element, or unresolved). */
    interface ComponentAt {
        ComponentIndex.Component at(int nameOffset);
    }

    /** What is under the cursor: a tag name or an attribute name, and its element. */
    private record Found(JasxElement element, JasxAttribute attribute) {}

    /** The Markdown for the markup at {@code offset}, or null. */
    static String at(String text, String path, int offset, Schema schema, ComponentAt components) {
        Found found = find(JasxParser.parse(text, path).unit().segments(), offset);
        if (found == null) {
            return null;
        }
        String tag = JasxParser.nameText(found.element().openingElement().name());
        ComponentIndex.Component component = components.at(found.element().openingElement().name().start());
        if (found.attribute() == null) {
            return component != null ? null : element(tag, schema); // a component's tag has its own hover
        }
        String attribute = JasxParser.attributeNameText(found.attribute().name());
        return component != null ? prop(component, attribute) : attribute(tag, attribute, schema);
    }

    // ---------------------------------------------------------------------------------------------
    // What to show
    // ---------------------------------------------------------------------------------------------

    private static String element(String tag, Schema schema) {
        Schema.ElementSpec spec = schema.element(tag);
        if (spec == null) {
            return null; // not an element of the target: JASX reports it
        }
        String kind = spec.isVoid() ? "A void element of the target (no children, no closing tag)."
                : "An element of the target.";
        return "```html\n<" + tag + ">\n```\n" + kind + " [MDN](https://developer.mozilla.org/docs/Web/HTML/Element/"
                + tag + ")";
    }

    private static String attribute(String tag, String name, Schema schema) {
        Schema.AttributeSpec spec = schema.attribute(tag, name);
        if (spec == null) {
            return null;
        }
        boolean global = schema.element(tag) == null || !schema.element(tag).attributes().containsKey(name);
        String kind = spec.event() ? "Event attribute: its value is the handler"
                : spec.isBoolean() ? "Boolean attribute: present or absent"
                : global ? "Global attribute" : "Attribute of `<" + tag + ">`";
        String link = spec.event() ? "" : global
                ? " [MDN](https://developer.mozilla.org/docs/Web/HTML/Global_attributes/" + name + ")"
                : " [MDN](https://developer.mozilla.org/docs/Web/HTML/Element/" + tag + "#" + name + ")";
        return "```html\n" + name + "\n```\n" + kind + "." + link;
    }

    private static String prop(ComponentIndex.Component component, String name) {
        ComponentIndex.Prop prop = component.prop(name);
        if (prop == null) {
            return null; // not a prop: JASX reports it
        }
        String how = switch (prop.kind()) {
            case REQUIRED -> "required";
            case OPTIONAL -> "optional";
            case DEFAULT -> "optional, default `" + prop.defaultValue() + "`";
            case CHILDREN -> "the children";
        };
        return "```java\n" + prop.type() + " " + prop.name() + "\n```\nProp of `" + component.method() + "` ("
                + component.className() + "), " + how + ".";
    }

    // ---------------------------------------------------------------------------------------------
    // Finding the markup under the cursor
    // ---------------------------------------------------------------------------------------------

    private static Found find(List<? extends Segment> segments, int offset) {
        for (Segment segment : segments) {
            Found found = switch (segment) {
                case Markup markup -> inMarkup(markup, offset);
                case JasxMarkupStatement statement -> inMarkup(statement.markup(), offset);
                default -> null; // Java code (its markup is a segment of its own) and style blocks
            };
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Found inMarkup(Markup markup, int offset) {
        if (!contains(markup, offset)) {
            return null;
        }
        return switch (markup) {
            case JasxElement element -> inElement(element, offset);
            case JasxFragment fragment -> inChildren(fragment.children(), offset);
        };
    }

    private static Found inElement(JasxElement element, int offset) {
        boolean onOpeningName = contains(element.openingElement().name(), offset);
        boolean onClosingName = element.closingElement() != null && contains(element.closingElement().name(), offset);
        if (onOpeningName || onClosingName) {
            return new Found(element, null);
        }
        for (JasxAttribute attribute : element.openingElement().attributes()) {
            if (contains(attribute.name(), offset)) {
                return new Found(element, attribute);
            }
            Found inValue = attribute.value() != null ? inValue(attribute.value(), offset) : null;
            if (inValue != null) {
                return inValue;
            }
        }
        return inChildren(element.children(), offset);
    }

    private static Found inValue(AttributeValue value, int offset) {
        return switch (value) {
            case Markup markup -> inMarkup(markup, offset);
            case JasxExpressionContainer container -> inExpression(container, offset);
            default -> null;
        };
    }

    private static Found inChildren(List<Child> children, int offset) {
        for (Child child : children) {
            Found found = switch (child) {
                case Markup markup -> inMarkup(markup, offset);
                case JasxExpressionContainer container -> inExpression(container, offset);
                case JasxStatementContainer container -> find(container.statement().segments(), offset);
                default -> null;
            };
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Found inExpression(JasxExpressionContainer container, int offset) {
        return container.expression() instanceof JavaExpression expression ? find(expression.segments(), offset) : null;
    }

    /** Whether {@code offset} is in the node or right after it (the cursor at the end of a name). */
    private static boolean contains(Node node, int offset) {
        return node.start() <= offset && offset <= node.end();
    }
}
