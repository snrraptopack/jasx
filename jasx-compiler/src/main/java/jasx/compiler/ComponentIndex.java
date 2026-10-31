package jasx.compiler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The component index (semantics.md §5.3): for each component, its declaring class, its name, and its
 * parameters in declaration order. Dependencies publish theirs as {@code META-INF/jasx/components.json}.
 */
public final class ComponentIndex {
    public static final String RESOURCE = "META-INF/jasx/components.json";

    public enum Children {
        NONE,
        SINGLE,
        LIST
    }

    public enum PropKind {
        REQUIRED,
        OPTIONAL,
        DEFAULT,
        /** The {@code children} parameter. */
        CHILDREN
    }

    /** A parameter. {@code defaultValue} is the literal source text of a {@code DEFAULT} prop. */
    public record Prop(String name, String type, PropKind kind, String defaultValue) {}

    public record Component(String className, String method, List<Prop> params, Children children) {
        /** The prop (not the children parameter) named {@code name}, or null. */
        public Prop prop(String name) {
            for (Prop p : params) {
                if (p.name().equals(name) && p.kind() != PropKind.CHILDREN) {
                    return p;
                }
            }
            return null;
        }
    }

    private final Map<String, Map<String, Component>> byClass = new LinkedHashMap<>();

    /** Adds a component; returns false (and keeps the existing one) if its class already has one with that name. */
    public boolean add(Component component) {
        return byClass.computeIfAbsent(component.className(), k -> new LinkedHashMap<>())
                .putIfAbsent(component.method(), component) == null;
    }

    public Component get(String className, String method) {
        return byClass.getOrDefault(className, Map.of()).get(method);
    }

    /** The components declared by {@code className}. */
    public Map<String, Component> inClass(String className) {
        return Collections.unmodifiableMap(byClass.getOrDefault(className, Map.of()));
    }

    public List<Component> all() {
        List<Component> all = new ArrayList<>();
        byClass.values().forEach(m -> all.addAll(m.values()));
        return all;
    }

    /** A new index with this index's components, plus those of {@code other} that this one doesn't have. */
    public ComponentIndex withFallback(ComponentIndex other) {
        ComponentIndex merged = new ComponentIndex();
        all().forEach(merged::add);
        other.all().forEach(merged::add);
        return merged;
    }

    /** Reads every {@code META-INF/jasx/components.json} visible to {@code loader}. */
    public static ComponentIndex fromClasspath(ClassLoader loader) {
        ComponentIndex index = new ComponentIndex();
        try {
            Enumeration<URL> resources = loader.getResources(RESOURCE);
            while (resources.hasMoreElements()) {
                String json = Resources.read(resources.nextElement());
                fromJson(json).all().forEach(index::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return index;
    }

    public static ComponentIndex fromJson(String json) {
        ComponentIndex index = new ComponentIndex();
        for (Object entry : Json.array(Json.parse(json))) {
            Map<String, Object> e = Json.object(entry);
            List<Prop> params = new ArrayList<>();
            for (Object p : Json.array(e.get("params"))) {
                Map<String, Object> param = Json.object(p);
                params.add(new Prop(Json.string(param.get("name")), Json.string(param.get("type")),
                        PropKind.valueOf(Json.string(param.get("kind")).toUpperCase(Locale.ROOT)),
                        Json.string(param.get("default"))));
            }
            index.add(new Component(Json.string(e.get("class")), Json.string(e.get("method")), List.copyOf(params),
                    Children.valueOf(Json.string(e.get("children")).toUpperCase(Locale.ROOT))));
        }
        return index;
    }

    /**
     * The {@code components.json} form of this index: a JSON array with one line per component, for
     * example
     *
     * <pre>
     * [
     *   {"class": "app.Ui", "method": "Chip", "params": [{"name": "label", "type": "String", "kind": "required"}], "children": "none"}
     * ]
     * </pre>
     */
    public String toJson() {
        List<String> lines = new ArrayList<>();
        for (Component component : all()) {
            lines.add("  " + componentJson(component));
        }
        if (lines.isEmpty()) {
            return "[]\n";
        }
        return "[\n" + String.join(",\n", lines) + "\n]\n";
    }

    private static String componentJson(Component component) {
        List<String> params = new ArrayList<>();
        for (Prop prop : component.params()) {
            params.add(propJson(prop));
        }
        return "{\"class\": " + Json.quote(component.className())
                + ", \"method\": " + Json.quote(component.method())
                + ", \"params\": [" + String.join(", ", params) + "]"
                + ", \"children\": " + Json.quote(lowercase(component.children()))
                + "}";
    }

    private static String propJson(Prop prop) {
        String json = "{\"name\": " + Json.quote(prop.name())
                + ", \"type\": " + Json.quote(prop.type())
                + ", \"kind\": " + Json.quote(lowercase(prop.kind()));
        if (prop.defaultValue() != null) {
            json += ", \"default\": " + Json.quote(prop.defaultValue());
        }
        return json + "}";
    }

    /** Enum constants are written in lowercase in JSON: {@code REQUIRED} becomes {@code "required"}. */
    private static String lowercase(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
