package jasx.web;

import jasx.runtime.Element;
import jasx.ui.Computation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A dynamic attribute of a page (targets/web.md §3): {@code class={expression}}. Its element is marked
 * {@code data-jx-id="N"} (§8.2), and when a value it read changes, its page re-runs it ({@link #update}) and
 * it sends the new value as an {@code attr} patch (§8.4).
 */
final class AttributeHole extends Computation {
    private final Page page;
    private final int elementId;
    private final String name;
    private final Supplier<?> value;
    /** The scope class of the element, for a {@code class} attribute of a scoped element; otherwise null. */
    private final String scopeClass;
    /** The value as last sent: null (absent), {@code Boolean.TRUE} (a bare attribute), or a string. */
    private Object current;

    AttributeHole(Page page, int elementId, String name, Supplier<?> value, String scopeClass) {
        super(page);
        this.page = page;
        this.elementId = elementId;
        this.name = name;
        this.value = value;
        this.scopeClass = scopeClass;
    }

    /** The first value, as part of the element's rendering. */
    Object firstValue() {
        current = track(this::evaluate);
        return current;
    }

    /** Whether it can ever change: only then does its element need an id (see {@link ChildHole#firstRender}). */
    boolean canChange() {
        return hasSources();
    }

    /** Evaluates again after a value it read changed, and records a patch if the value changed. */
    @Override
    public void update() {
        Object newValue = retrack(this::evaluate); // on failure, keeps its previous value (ui.md §2.4)
        if (!Objects.equals(newValue, current)) {
            current = newValue;
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("op", "attr");
            patch.put("id", elementId);
            patch.put("name", name);
            patch.put("value", newValue);
            page.addPatch(patch);
        }
    }

    private Object evaluate() {
        return normalize(page, value.get(), scopeClass);
    }

    /**
     * An attribute value as it is written (targets/html.md §3.1): null or false leave the attribute out,
     * true writes it bare, markup is rendered to HTML, anything else is its string form. On a scoped element,
     * the class attribute also gets the scope class (semantics.md §10.5).
     *
     * @return null (absent), {@code Boolean.TRUE} (bare), or a string
     */
    static Object normalize(Page page, Object value, String scopeClass) {
        Object written;
        if (value == null || Boolean.FALSE.equals(value)) {
            written = null;
        } else if (Boolean.TRUE.equals(value)) {
            written = Boolean.TRUE;
        } else if (value instanceof Element element) {
            written = Renderer.staticHtml(page, element);
        } else {
            written = String.valueOf(value);
        }

        if (scopeClass == null) {
            return written;
        }
        boolean noClassText = written == null || written == Boolean.TRUE;
        return noClassText ? scopeClass : written + " " + scopeClass;
    }
}
