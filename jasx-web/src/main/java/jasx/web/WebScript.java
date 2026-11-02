package jasx.web;

import jasx.ui.Action;
import jasx.ui.Computation;
import jasx.ui.Derived;
import jasx.ui.Handler;
import jasx.ui.State;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A browser attribute's value (targets/web.md §7.1), as lowered markup builds it with {@link Web#script}: the module
 * and the function to call in the browser, and the payload. The page renders it as JSON in a
 * {@code data-jx-js-<event>} attribute ({@link #json}).
 *
 * @param module   the file's module, such as {@code web/Menu.jasx.js}
 * @param function the function to call, a top-level name of the module's script or an inline snippet's function
 * @param payload  the payload: refs, Java values, objects and lists; null for none
 */
record WebScript(String module, String function, Object payload) {

    /** A ref of a payload ({@link Web#ref}): the element the browser finds by its {@code data-jx-ref}. */
    record Ref(String name) {}

    /** An object of a payload ({@link Web#object}): keys and values, in order. */
    record PayloadObject(Map<String, Object> entries) {}

    /** {@code ["web/Menu.jasx.js", "toggleMenu", {...}]}, with the payload converted ({@link #toJson}). */
    String json(Page page) {
        List<Object> call = new ArrayList<>();
        call.add(module);
        call.add(function);
        call.add(toJson(page, payload));
        return Json.write(call);
    }

    /**
     * A payload value as JSON (targets/web.md §7.1): a ref as {@code {"$ref": name}}, a Java handler as
     * {@code {"handler": id}} (registered with the page, owned by the computation rendering it), a {@code State} or
     * {@code Derived} as its value (read, so the attribute follows it), records by component name, enums by name,
     * lists, arrays and maps as they are, strings, numbers and booleans as they are, anything else as its string.
     */
    static Object toJson(Page page, Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Number n -> n;
            case Boolean b -> b;
            case Character c -> String.valueOf(c);
            case Enum<?> e -> e.name();
            case Ref ref -> Map.of("$ref", ref.name());
            case PayloadObject object -> {
                Map<String, Object> json = new LinkedHashMap<>();
                object.entries().forEach((key, entry) -> json.put(key, toJson(page, entry)));
                yield json;
            }
            case State<?> state -> toJson(page, state.get());
            case Derived<?> derived -> toJson(page, derived.get());
            case Handler<?> handler -> Map.of("handler", page.addHandler(handler, Computation.current()));
            case Action action -> Map.of("handler", page.addHandler(action, Computation.current()));
            case Map<?, ?> map -> {
                Map<String, Object> json = new LinkedHashMap<>();
                map.forEach((key, entry) -> json.put(String.valueOf(key), toJson(page, entry)));
                yield json;
            }
            case Iterable<?> items -> {
                List<Object> json = new ArrayList<>();
                items.forEach(item -> json.add(toJson(page, item)));
                yield json;
            }
            case Record record -> recordJson(page, record);
            default -> value.getClass().isArray() ? arrayJson(page, value) : value.toString();
        };
    }

    private static List<Object> arrayJson(Page page, Object array) {
        List<Object> json = new ArrayList<>();
        for (int i = 0; i < Array.getLength(array); i++) {
            json.add(toJson(page, Array.get(array, i)));
        }
        return json;
    }

    private static Map<String, Object> recordJson(Page page, Record record) {
        Map<String, Object> json = new LinkedHashMap<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                json.put(component.getName(), toJson(page, accessor.invoke(record)));
            } catch (ReflectiveOperationException | RuntimeException e) {
                json.put(component.getName(), null); // a component that can't be read: left empty
            }
        }
        return json;
    }
}
