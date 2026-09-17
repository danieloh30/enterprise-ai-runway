package com.danieloh.demo.runtime;

import com.danieloh.demo.shared.Secrets;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Map;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiAuthentication implements ContainerRequestFilter {
    @ConfigProperty(name="runway.demo-key") String key;
    @ConfigProperty(name="quarkus.oidc.tenant-enabled") boolean oidc;
    @Inject SecurityIdentity identity;
    @Override public void filter(ContainerRequestContext request) {
        String path=request.getUriInfo().getPath().replaceFirst("^/+", "");
        if (!path.startsWith("api/")) return;
        if (oidc) {
            boolean decision=path.endsWith("/approve") || path.endsWith("/reject");
            if(identity.isAnonymous()) request.abortWith(Response.status(401).entity(Map.of("error","A valid OIDC access token is required")).build());
            else if(!identity.hasRole("presenter") || (decision && !identity.hasRole("approver")))
                request.abortWith(Response.status(403).entity(Map.of("error","Required role is missing")).build());
        } else if (!Secrets.matches(request.getHeaderString("Authorization"), "Bearer " + key))
            request.abortWith(Response.status(401).entity(Map.of("error","Enter the presenter access key from ./demo.sh credentials")).build());
    }
}
