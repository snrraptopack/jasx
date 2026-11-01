package jasx.web.spring;

import jasx.web.WebApp;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * Supplies Spring MVC's handler adapter in Spring Boot 3: one that runs controller methods returning an
 * {@code Element} inside a new page ({@link PageHandlerAdapter}). Spring Boot uses a single
 * {@code WebMvcRegistrations}, so if the application defines its own, this one steps aside, and pages are returned
 * as {@link WebPage}s instead.
 *
 * <p>Spring Boot 3 keeps {@code WebMvcRegistrations} in {@code org.springframework.boot.autoconfigure.web.servlet}. This class is used only when that
 * type exists: Spring Boot reads the condition from the class file without loading the class, so in the other
 * version it is never loaded. {@link JasxWebAutoConfiguration} has everything that doesn't depend on the version.
 */
@AutoConfiguration(after = JasxWebAutoConfiguration.class, beforeName = "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration")
@ConditionalOnClass(name = "org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations")
@ConditionalOnBean(WebApp.class)
public class Boot3MvcRegistrations {
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
}
