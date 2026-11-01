package jasx.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A dynamic child of a page (targets/web.md §3): {@code {expression}} or a statement child. It renders its
 * value between the markers {@code <!--jx:N-->} and {@code <!--/jx:N-->} (§8.2), and when a value it read
 * changes, it renders again and sends the new content as an {@code html} patch (§8.4).
 */
final class ChildHole extends Computation {
    final int id;
    private final Supplier<Object> value;
    /** The content as last sent to the browser, to skip patches that change nothing. */
    private String html;

    ChildHole(Page page, int id, Supplier<Object> value) {
        super(page);
        this.id = id;
        this.value = value;
    }

    /**
     * The first rendering, as part of {@code parent}: the content between its markers. A hole that read no
     * reactive value (such as {@code {todo.title()}}) can never change, so it needs no markers; holes inside
     * its content still get theirs.
     */
    String firstRender(Renderer parent) {
        Renderer content = renderContent();
        parent.useSheets(content.sheets());
        html = content.html();
        if (!hasSources()) {
            return html;
        }
        return "<!--jx:" + id + "-->" + html + "<!--/jx:" + id + "-->";
    }

    @Override
    void invalidate() {
        page.markDirty(this);
    }

    /** Renders again after a value it read changed, and records a patch if the content changed. */
    void rerun() {
        reset();
        Renderer content = renderContent();
        page.useSheets(content.sheets()); // a css patch for any style sheet the page didn't have (§9)
        String newHtml = content.html();
        if (!newHtml.equals(html)) {
            html = newHtml;
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("op", "html");
            patch.put("id", id);
            patch.put("html", newHtml);
            page.addPatch(patch);
        }
    }

    /**
     * Evaluates the value and renders it, both tracked: components called by the expression, and lazy
     * values such as streams consumed while rendering, subscribe this hole to what they read.
     */
    private Renderer renderContent() {
        return track(() -> {
            Renderer content = new Renderer(page, false);
            content.child(value.get());
            return content;
        });
    }
}
