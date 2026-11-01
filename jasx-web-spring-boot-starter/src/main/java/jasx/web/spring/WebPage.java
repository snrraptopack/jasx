package jasx.web.spring;

/**
 * A page as a function, for when a controller method can't simply return the markup:
 *
 * <pre>
 *   &#64;GetMapping("/todos/{id}")
 *   WebPage todo(&#64;PathVariable long id) {
 *       return () -&gt; &lt;TodoDetail todo={store.get(id)} /&gt;;
 *   }
 * </pre>
 *
 * <p>Usually a controller method returns the {@code Element} itself, and the starter runs the whole method inside
 * the page. That needs the starter's Spring MVC handler adapter; when another {@code WebMvcRegistrations} replaces
 * it, return a {@code WebPage}. Either way the page's components run inside the page, so the state and effects
 * they create belong to it and are disposed with it (targets/web.md §4.2, §6). The starter calls it once.
 */
@FunctionalInterface
public interface WebPage {
    /** The page's root: usually an {@code <html>} element. */
    Object render();
}
