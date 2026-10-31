package jasx.compiler;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** A target's element and attribute schema (semantics.md §7.3). */
public final class Schema {
    /** An attribute: its builder method and whether it is an event attribute (schema kind {@code event}). */
    public record AttributeSpec(String method, boolean event) {}

    /** An intrinsic element: whether it is void, and its own (non-global) attributes. */
    public record ElementSpec(boolean isVoid, Map<String, AttributeSpec> attributes) {}

    private final Map<String, ElementSpec> elements;
    private final Map<String, AttributeSpec> globalAttributes;
    private final List<String> attributePrefixes;
    private final boolean customElements;

    private Schema(Map<String, ElementSpec> elements, Map<String, AttributeSpec> globalAttributes,
            List<String> attributePrefixes, boolean customElements) {
        this.elements = elements;
        this.globalAttributes = globalAttributes;
        this.attributePrefixes = attributePrefixes;
        this.customElements = customElements;
    }

    static Schema fromJson(Map<String, Object> json) {
        Map<String, AttributeSpec> globals = new HashMap<>(attributes(Json.object(json.get("globalAttributes"))));
        for (Object event : Json.array(json.get("eventAttributes"))) {
            String name = (String) event;
            globals.put(name, new AttributeSpec(camelCase(name), true));
        }
        Map<String, ElementSpec> elements = new LinkedHashMap<>();
        Json.object(json.get("elements")).forEach((tag, value) -> {
            Map<String, Object> element = Json.object(value);
            elements.put(tag, new ElementSpec(Boolean.TRUE.equals(element.get("void")),
                    attributes(Json.object(element.get("attributes")))));
        });
        // Patterns are written as "data-*": a prefix followed by '*'.
        List<String> prefixes = Json.array(json.get("attributePatterns")).stream()
                .map(p -> ((String) p).replace("*", ""))
                .toList();
        return new Schema(elements, globals, prefixes, Boolean.TRUE.equals(json.get("customElements")));
    }

    private static Map<String, AttributeSpec> attributes(Map<String, Object> json) {
        Map<String, AttributeSpec> result = new HashMap<>();
        json.forEach((name, value) -> {
            String method = Json.string(Json.object(value).get("method"));
            result.put(name, new AttributeSpec(method != null ? method : camelCase(name), false));
        });
        return result;
    }

    /** The element, or null if the schema doesn't define it. */
    public ElementSpec element(String tag) {
        return elements.get(tag);
    }

    /** The attribute of {@code tag} (its own, then global), or null if the schema doesn't define it. */
    public AttributeSpec attribute(String tag, String name) {
        ElementSpec element = elements.get(tag);
        if (element != null && element.attributes().containsKey(name)) {
            return element.attributes().get(name);
        }
        return globalAttributes.get(name);
    }

    /** Whether {@code name} matches an extension pattern such as {@code data-*}. */
    public boolean matchesPattern(String name) {
        return attributePrefixes.stream().anyMatch(name::startsWith);
    }

    public boolean customElements() {
        return customElements;
    }

    public Set<String> tags() {
        return elements.keySet().stream().collect(Collectors.toUnmodifiableSet());
    }

    /** {@code tab-index} becomes {@code tabIndex} (semantics.md §6.3). */
    static String camelCase(String name) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '-') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    /** {@code my-widget} becomes {@code MyWidget}: the builder class name of a tag. */
    static String pascalCase(String tag) {
        String camel = camelCase(tag);
        return camel.isEmpty() ? camel : Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }
}
