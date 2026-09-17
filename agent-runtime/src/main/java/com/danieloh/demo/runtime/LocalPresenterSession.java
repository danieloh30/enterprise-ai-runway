package com.danieloh.demo.runtime;

import com.danieloh.demo.shared.Secrets;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/** Explicit local-demo convenience. Never exposes credentials loaded from .env. */
@Path("/local-session")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class LocalPresenterSession {
    @ConfigProperty(name = "runway.local-auto-connect", defaultValue = "false") boolean enabled;
    @ConfigProperty(name = "quarkus.oidc.tenant-enabled") boolean oidc;
    private final String token;

    public LocalPresenterSession() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @POST
    public Response connect(@Context UriInfo uri,
                            @HeaderParam("Origin") String origin,
                            @HeaderParam("Sec-Fetch-Site") String site,
                            @HeaderParam("X-Runway-Local") String marker) {
        URI base = uri.getBaseUri();
        if (!available(base)) return reply(404, Map.of("error", "Local connection is disabled"));
        // Origin + a custom header prevent cross-site forms/fetches from obtaining a token.
        // A literal loopback host also rejects browser requests through DNS rebinding names.
        String expectedOrigin = base.getScheme() + "://" + base.getRawAuthority();
        if (!expectedOrigin.equals(origin) || !"1".equals(marker) || !"same-origin".equals(site)) {
            return reply(403, Map.of("error", "Open the demo directly on localhost to connect"));
        }
        return reply(200, Map.of("token", token));
    }

    boolean authenticates(URI base, String authorization) {
        return available(base) && Secrets.matches(authorization, "Bearer " + token);
    }

    private boolean available(URI base) {
        return enabled && !oidc && base.getUserInfo() == null && base.getHost() != null
            && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(base.getHost())
            && Set.of("http", "https").contains(base.getScheme());
    }

    private Response reply(int status, Map<String, String> body) {
        return Response.status(status).entity(body).header("Cache-Control", "no-store").build();
    }
}
