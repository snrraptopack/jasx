package jasx.web;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Base class of the generated attribute builders ({@code jasx.web.attrs.*}, targets/web.md §2). Attributes
 * are kept in the order they were set, which is their source order.
 *
 * <p>A value is a static value, a {@link Supplier} for a dynamic attribute (re-evaluated when the state it
 * reads changes), or a {@link Handler} for an event attribute.
 */
public abstract class Attributes<T extends Attributes<T>> {
    private final List<String> names = new ArrayList<>();
    private final List<Object> values = new ArrayList<>();

    protected Attributes() {}

    /** Sets an attribute by name: {@code data-*}, {@code aria-*}, schema extensions and {@code ns:name}. */
    public final T attr(String name, Object value) {
        return set(name, value);
    }

    /** Sets a dynamic attribute by name. */
    public final T attr(String name, Supplier<?> value) {
        return set(name, value);
    }

    @SuppressWarnings("unchecked")
    protected final T set(String name, Object value) {
        names.add(name);
        values.add(value);
        return (T) this;
    }

    int size() {
        return names.size();
    }

    String name(int i) {
        return names.get(i);
    }

    Object value(int i) {
        return values.get(i);
    }
}
