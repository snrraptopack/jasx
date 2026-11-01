package jasx.web.spring;

import jasx.web.WebApp;
import jasx.web.servlet.JasxServlet;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * Sets up the web target in a Spring MVC application (targets/web.md), on {@code jasx-web-servlet}:
 *
 * <ul>
 *   <li>a {@link WebApp} bean, from the {@link JasxWebProperties} (define your own bean to replace it);
 *   <li>the runtime's own requests (client script, events, streams, pings) under {@code jasx.web.base-path},
 *       served by an async servlet, so open streams don't hold the container's threads;
 *   <li>controller methods that return an {@code Element} run inside a new page, which is sent as the response
 *       ({@link PageHandlerAdapter}); a {@link WebPage} return value works too, and is the fallback when another
 *       {@code WebMvcRegistrations} replaces the starter's.
 * </ul>
 *
 * <p>The security checks are the {@link WebApp}'s (targets/web.md §6.3): sessions, same-site checks and limits.
 * With Spring Security, exclude the base path from Spring's CSRF check, which the runtime does its own way.
 */
@AutoConfiguration(before = WebMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RequestMappingHandlerAdapter.class)
@EnableConfigurationProperties(JasxWebProperties.class)
public class JasxWebAutoConfiguration {
    /** The pages of the application. Its base path includes the context path, because the browser requests it. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public WebApp jasxWebApp(JasxWebProperties properties, @Value("${server.servlet.context-path:}") String contextPath) {
        String context = contextPath.endsWith("/") ? contextPath.substring(0, contextPath.length() - 1) : contextPath;
        return new WebApp(context + properties.basePath(), properties.pageTimeout(), properties.firstConnectTimeout(),
                new WebApp.Limits(properties.maxEventBytes(), properties.maxPagesPerSession(), properties.maxUploadBytes()));
    }

    /** The runtime's own requests, at {@code <base path>/*} within the application. */
    @Bean
    public ServletRegistrationBean<JasxServlet> jasxServlet(WebApp app, JasxWebProperties properties) {
        ServletRegistrationBean<JasxServlet> registration =
                new ServletRegistrationBean<>(new JasxServlet(app), properties.basePath() + "/*");
        registration.setName("jasx");
        registration.setAsyncSupported(true);
        return registration;
    }

    /**
     * Closes the {@link WebApp} as soon as the application starts to stop. Its streams are requests that stay open
     * until the browser leaves, so the web server's graceful shutdown would otherwise wait for them until it times
     * out. Closing ends them at once; the browsers reconnect, and reload, when the application is back.
     */
    @Bean
    public SmartLifecycle jasxWebAppShutdown(WebApp app) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                app.close();
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            /** The highest phase stops first: before the web server's graceful shutdown. */
            @Override
            public int getPhase() {
                return Integer.MAX_VALUE;
            }
        };
    }

    /**
     * Supplies Spring MVC's handler adapter: one that runs controller methods returning an {@code Element} inside a
     * new page ({@link PageHandlerAdapter}). Spring Boot uses a single {@code WebMvcRegistrations}, so if the
     * application defines its own, this one steps aside, and pages are returned as {@link WebPage}s instead.
     */
    @Bean
    @ConditionalOnMissingBean(WebMvcRegistrations.class)
    public WebMvcRegistrations jasxWebMvcRegistrations(WebApp app) {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerAdapter getRequestMappingHandlerAdapter() {
                return new PageHandlerAdapter(app);
            }
        };
    }

    /**
     * Puts the page handler first among Spring MVC's return value handlers, so that pages render even from a
     * {@code @RestController}, whose body handler would otherwise claim them.
     */
    @Bean
    public SmartInitializingSingleton jasxPageHandler(ObjectProvider<RequestMappingHandlerAdapter> adapters, WebApp app) {
        return () -> adapters.forEach(adapter -> {
            if (!(adapter instanceof PageHandlerAdapter)) {
                LOG.log(System.Logger.Level.WARNING, "another WebMvcRegistrations replaces the JASX starter's: controller"
                        + " methods must return a WebPage (return () -> <Page />), not an Element");
            }
            List<HandlerMethodReturnValueHandler> handlers = new ArrayList<>();
            handlers.add(new PageReturnValueHandler(app));
            List<HandlerMethodReturnValueHandler> existing = adapter.getReturnValueHandlers();
            if (existing != null) {
                handlers.addAll(existing);
            }
            adapter.setReturnValueHandlers(handlers);
        });
    }

    private static final System.Logger LOG = System.getLogger(JasxWebAutoConfiguration.class.getName());
}
