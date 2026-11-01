package jasx.web.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jasx.web.WebApp;
import jasx.web.servlet.ServletAdapter;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Renders the {@link WebPage} a controller method returns: a new page of the {@link WebApp}, written to the
 * response with its session cookie if the browser had none (targets/web.md §6.3). Spring writes nothing else.
 */
final class WebPageReturnValueHandler implements HandlerMethodReturnValueHandler {
    private final WebApp app;

    WebPageReturnValueHandler(WebApp app) {
        this.app = app;
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        return WebPage.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public void handleReturnValue(Object returnValue, MethodParameter returnType, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest) throws Exception {
        mavContainer.setRequestHandled(true); // no view to resolve: the response is complete
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        HttpServletResponse response = webRequest.getNativeResponse(HttpServletResponse.class);
        if (returnValue == null || request == null || response == null) {
            throw new IllegalStateException("a WebPage needs a servlet request and a page to render");
        }
        WebPage page = (WebPage) returnValue;
        ServletAdapter.render(app, request, response, page::render);
    }
}
