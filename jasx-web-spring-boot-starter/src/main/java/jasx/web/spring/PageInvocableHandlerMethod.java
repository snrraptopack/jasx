package jasx.web.spring;

import jakarta.servlet.http.HttpServletRequest;
import jasx.web.WebApp;
import jasx.web.servlet.ServletAdapter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;

/**
 * Calls a controller method that returns a {@code jasx.runtime.Element} <em>inside</em> a new page: Spring binds
 * the method's arguments first, then the method body runs within {@link WebApp#render}, so the components it calls
 * create their state and effects for that page (targets/web.md §4.2, §6). The result is a {@link RenderedPage},
 * which {@link PageReturnValueHandler} sends.
 *
 * <p>An exception from the method discards the page and reaches Spring as usual: a
 * {@code ResponseStatusException} is still a 404, an {@code @ExceptionHandler} still applies.
 */
final class PageInvocableHandlerMethod extends ServletInvocableHandlerMethod {
    private final WebApp app;

    PageInvocableHandlerMethod(HandlerMethod method, WebApp app) {
        super(method);
        this.app = app;
    }

    @Override
    public Object invokeForRequest(NativeWebRequest request, ModelAndViewContainer mavContainer, Object... providedArgs)
            throws Exception {
        Object[] args = getMethodArgumentValues(request, mavContainer, providedArgs);
        HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);
        if (servletRequest == null) {
            throw new IllegalStateException("a page needs a servlet request");
        }
        try {
            return new RenderedPage(app.render(ServletAdapter.request(servletRequest), () -> call(args)));
        } catch (MethodFailed failed) {
            throw failed.exception; // the method's own checked exception, unwrapped for Spring
        }
    }

    /** Calls the method; a checked exception travels through the rendering wrapped, and is unwrapped above. */
    private Object call(Object[] args) {
        try {
            return doInvoke(args);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new MethodFailed(e);
        }
    }

    /** Carries a checked exception of the controller method out of the rendering. */
    private static final class MethodFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;
        @SuppressWarnings("serial") // never serialized: it only crosses one method call
        final Exception exception;

        MethodFailed(Exception exception) {
            super(exception);
            this.exception = exception;
        }
    }
}
