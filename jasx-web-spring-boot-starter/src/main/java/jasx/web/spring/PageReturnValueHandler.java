package jasx.web.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jasx.runtime.Element;
import jasx.web.WebApp;
import jasx.web.servlet.ServletAdapter;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Sends the pages controller methods return, written to the response with the browser's session cookie if it had
 * none (targets/web.md §6.3). Spring writes nothing else.
 *
 * <ul>
 *   <li>a {@link RenderedPage}: what a method declared to return an {@link Element} produced, already rendered
 *       inside its page ({@link PageInvocableHandlerMethod});
 *   <li>a {@link WebPage}: rendered here, inside a new page.
 * </ul>
 *
 * An {@link Element} that arrives unrendered means the starter's handler adapter isn't the one in use (another
 * {@code WebMvcRegistrations} supplies Spring's): that is reported with what to do instead.
 */
final class PageReturnValueHandler implements HandlerMethodReturnValueHandler {
    private final WebApp app;

    PageReturnValueHandler(WebApp app) {
        this.app = app;
    }

    /** Spring passes the type of the value returned, so a rendered page is recognized by its own type. */
    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        Class<?> type = returnType.getParameterType();
        return RenderedPage.class.isAssignableFrom(type) || WebPage.class.isAssignableFrom(type)
                || Element.class.isAssignableFrom(type);
    }

    @Override
    public void handleReturnValue(Object returnValue, MethodParameter returnType, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest) throws Exception {
        mavContainer.setRequestHandled(true); // no view to resolve: the response is complete
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        HttpServletResponse response = webRequest.getNativeResponse(HttpServletResponse.class);
        if (request == null || response == null) {
            throw new IllegalStateException("a page needs a servlet request and response");
        }
        switch (returnValue) {
            case RenderedPage rendered -> ServletAdapter.send(response, rendered.response());
            case WebPage page -> ServletAdapter.render(app, request, response, page::render);
            case Element element -> throw new IllegalStateException(returnType.getMethod()
                    + " returned an Element, but another WebMvcRegistrations replaces the JASX starter's, so it couldn't"
                    + " run inside a page: return a WebPage instead (return () -> <Page />)");
            case null -> throw new IllegalStateException(returnType.getMethod() + " returned no page");
            default -> throw new IllegalStateException("not a page: " + returnValue);
        }
    }
}
