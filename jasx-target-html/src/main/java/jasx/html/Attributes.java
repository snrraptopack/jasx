package jasx.html;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class of the generated attribute builders ({@code jasx.html.attrs.*}). Attributes are kept in the
 * order they were set, which is their source order (targets/html.md §3.1).
 */
public abstract class Attributes<T extends Attributes<T>> {
    private final List<String> names = new ArrayList<>();
    private final List<Object> values = new ArrayList<>();

    protected Attributes() {}

    /** Sets an attribute by name: {@code data-*}, {@code aria-*}, schema extensions and {@code ns:name}. */
    public final T attr(String name, Object value) {
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
