package com.danieloh.demo.runtime.security;

import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalPresenterSessionTest {
    private UriInfo uri(String base) {
        var info = mock(UriInfo.class);
        when(info.getBaseUri()).thenReturn(URI.create(base));
        return info;
    }

    @Test void localTokenCannotAuthenticateOnRemoteHostsOrAfterRestart() {
        var sessions = new LocalPresenterSession(); sessions.enabled = true;
        var response = sessions.connect(uri("http://localhost:8090/"), "http://localhost:8090", "same-origin", "1");
        String token = (String) ((Map<?, ?>) response.getEntity()).get("token");
        assertEquals(200, response.getStatus());
        assertTrue(sessions.authenticates(URI.create("http://localhost:8090/"), "Bearer " + token));
        assertFalse(sessions.authenticates(URI.create("https://demo.example/"), "Bearer " + token));
        var restarted = new LocalPresenterSession(); restarted.enabled = true;
        assertFalse(restarted.authenticates(URI.create("http://localhost:8090/"), "Bearer " + token));
    }

    @Test void oidcAlwaysDisablesLocalSessionIssuanceAndAuthentication() {
        var sessions = new LocalPresenterSession(); sessions.enabled = true;
        var base = uri("http://localhost:8090/");
        var response = sessions.connect(base, "http://localhost:8090", "same-origin", "1");
        String token = (String) ((Map<?, ?>) response.getEntity()).get("token");
        sessions.oidc = true;
        assertEquals(404, sessions.connect(base, "http://localhost:8090", "same-origin", "1").getStatus());
        assertFalse(sessions.authenticates(base.getBaseUri(), "Bearer " + token));
    }

    @Test void loopbackAddressesWorkButDnsRebindingHostnamesDoNot() {
        var sessions = new LocalPresenterSession(); sessions.enabled = true;
        for (String host : new String[]{"localhost", "127.0.0.1", "[::1]"}) {
            String origin = "http://" + host + ":8090";
            assertEquals(200, sessions.connect(uri(origin + "/"), origin, "same-origin", "1").getStatus());
        }
        assertEquals(404, sessions.connect(uri("http://attacker.example:8090/"),
            "http://attacker.example:8090", "same-origin", "1").getStatus());
    }
}
