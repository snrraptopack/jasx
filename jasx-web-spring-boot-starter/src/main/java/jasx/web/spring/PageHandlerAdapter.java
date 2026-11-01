package jasx.web.spring;

import jasx.runtime.Element;
import jasx.web.WebApp;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;

/**
 * Spring MVC's handler adapter, except that controller methods declared to return an {@link Element} run inside a
 * new page ({@link PageInvocableHandlerMethod}). Every other method is called exactly as Spring calls it.
 * The starter supplies it through {@code WebMvcRegistrations}.
 */
final class PageHandlerAdapter extends RequestMappingHandlerAdapter {
    private final WebApp app;

    PageHandlerAdapter(WebApp app) {
        this.app = app;
    }

    @Override
    protected ServletInvocableHandlerMethod createInvocableHandlerMethod(HandlerMethod handlerMethod) {
        boolean returnsPage = Element.class.isAssignableFrom(handlerMethod.getReturnType().getParameterType());
        return returnsPage ? new PageInvocableHandlerMethod(handlerMethod, app) : super.createInvocableHandlerMethod(handlerMethod);
    }
}
