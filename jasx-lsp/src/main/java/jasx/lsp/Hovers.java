package jasx.lsp;

import java.util.ArrayList;
import java.util.List;
import jasx.compiler.ComponentIndex;
import jasx.compiler.ComponentIndex.Prop;

/**
 * The Markdown shown when hovering over a component tag: where the component is declared, its
 * signature, and how each parameter is passed (jasx-spec/semantics.md §5.2).
 *
 * <pre>
 *   ```java
 *   app.ui.Layout.Badge(String text, String tone)
 *   ```
 *   - `text`: required
 *   - `tone`: optional, default `"neutral"`
 * </pre>
 */
final class Hovers {
    private Hovers() {}

    static String component(ComponentIndex.Component component) {
        List<String> parameters = new ArrayList<>();
        List<String> props = new ArrayList<>();
        for (Prop prop : component.params()) {
            parameters.add(prop.type() + " " + prop.name());
            props.add("- `" + prop.name() + "`: " + describe(prop, component.children()));
        }

        StringBuilder markdown = new StringBuilder();
        markdown.append("```java\n")
                .append(component.className()).append('.').append(component.method())
                .append('(').append(String.join(", ", parameters)).append(")\n")
                .append("```\n");
        if (props.isEmpty()) {
            markdown.append("No props, no children.");
        } else {
            markdown.append(String.join("\n", props));
        }
        return markdown.toString();
    }

    /** How a parameter is given in markup. */
    private static String describe(Prop prop, ComponentIndex.Children children) {
        return switch (prop.kind()) {
            case REQUIRED -> "required";
            case OPTIONAL -> "optional";
            case DEFAULT -> "optional, default `" + prop.defaultValue() + "`";
            case CHILDREN -> children == ComponentIndex.Children.SINGLE
                    ? "the child between the tags (exactly one)"
                    : "the children between the tags";
        };
    }
}
