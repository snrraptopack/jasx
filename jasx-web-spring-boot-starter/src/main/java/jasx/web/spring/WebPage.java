package jasx.web.spring;

/**
 * A page, as a Spring MVC controller method returns it:
 *
 * <pre>
 *   &#64;Controller
 *   class Pages {
 *       &#64;GetMapping("/todos/{id}")
 *       WebPage todo(&#64;PathVariable long id) {
 *           return () -&gt; &lt;TodoDetail todo={store.get(id)} /&gt;;
 *       }
 *   }
 * </pre>
 *
 * <p>It is a function, not the markup itself, because the page's components must run inside the page: the state
 * and effects they create then belong to it, and are disposed with it (targets/web.md §4.2, §6). The starter
 * calls it once, when it renders the page.
 */
@FunctionalInterface
public interface WebPage {
    /** The page's root: usually an {@code <html>} element. */
    Object render();
}
