package jasx.web.spring;

import jasx.web.WebApp;

/** A page a controller method rendered ({@link PageInvocableHandlerMethod}): the response to send as it is. */
record RenderedPage(WebApp.Response response) {}
