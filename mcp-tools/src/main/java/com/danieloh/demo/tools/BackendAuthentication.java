package com.danieloh.demo.tools;

import com.danieloh.demo.shared.Secrets;
import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class BackendAuthentication {
    @ConfigProperty(name="runway.backend-key") String key;
    @RouteFilter(100)
    void authenticate(RoutingContext context) {
        if (context.normalizedPath().startsWith("/mcp") && !Secrets.matches(context.request().getHeader("Authorization"), "Bearer " + key)) {
            context.response().setStatusCode(401).putHeader("Content-Type", "application/json").end("{\"error\":\"Backend credential required\"}");
        } else context.next();
    }
}
