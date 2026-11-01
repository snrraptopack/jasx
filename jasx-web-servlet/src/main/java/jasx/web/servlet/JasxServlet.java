package jasx.web.servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jasx.web.WebApp;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A servlet that serves web-target pages and the runtime's own requests (targets/web.md §8.1) on any Jakarta
 * Servlet 6 container:
 *
 * <pre>
 *   JasxServlet servlet = new JasxServlet(new WebApp())
 *       .page("/", () -&gt; &lt;Home /&gt;)
 *       .page("/todos", request -&gt; &lt;Todos filter={request.getParameter("filter")} /&gt;);
 *
 *   ServletRegistration.Dynamic registration = servletContext.addServlet("jasx", servlet);
 *   registration.addMapping("/*");
 *   registration.setAsyncSupported(true);   // streams then don't hold the container's threads
 * </pre>
 *
 * <p>Pages are matched by their exact path within the application (without the context path). Other paths answer
 * 404. Destroying the servlet closes its {@link WebApp}.
 */
// HttpServlet is Serializable, but containers don't serialize servlets here, and a WebApp can't be.
@SuppressWarnings("serial")
public final class JasxServlet extends HttpServlet {
    private final WebApp app;
    private final Map<String, Function<HttpServletRequest, ?>> pages = new ConcurrentHashMap<>();

    public JasxServlet(WebApp app) {
        this.app = app;
    }

    /** Serves the page {@code root} at {@code path}. */
    public JasxServlet page(String path, Supplier<?> root) {
        return page(path, request -> root.get());
    }

    /** Serves at {@code path} the page {@code root} makes from the request (its parameters, say). */
    public JasxServlet page(String path, Function<HttpServletRequest, ?> root) {
        pages.put(path, root);
        return this;
    }

    public WebApp app() {
        return app;
    }

    /** Checks that the app's base path is under the application's context path, where the browser can reach it. */
    @Override
    public void init() throws ServletException {
        String contextPath = getServletContext().getContextPath();
        if (!contextPath.isEmpty() && !app.basePath().startsWith(contextPath + "/")) {
            throw new ServletException("the WebApp's base path " + app.basePath() + " is outside the context path "
                    + contextPath + ": create it with new WebApp(\"" + contextPath + "/_jasx\", ...)");
        }
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (ServletAdapter.handle(app, request, response)) {
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Function<HttpServletRequest, ?> root = pages.get(path);
        if (root != null && request.getMethod().equals("GET")) {
            ServletAdapter.render(app, request, response, () -> root.apply(request));
        } else {
            ServletAdapter.send(response, new WebApp.Response(404, "Not found",
                    Map.of("Content-Type", "text/plain; charset=utf-8")));
        }
    }

    @Override
    public void destroy() {
        app.close();
    }
}
