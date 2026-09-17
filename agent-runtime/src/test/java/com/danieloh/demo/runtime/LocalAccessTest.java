package com.danieloh.demo.runtime;

import com.danieloh.demo.shared.Database;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestProfile(LocalAccessTest.LocalProfile.class)
class LocalAccessTest {
    public static class LocalProfile implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("runway.local-auto-connect", "true");
        }
    }
    @InjectMock Database db;
    @InjectMock RunService runs;
    @InjectMock GatewayClient gateway;
    @TestHTTPResource URI base;

    private String origin() { return base.getScheme() + "://" + base.getRawAuthority(); }

    @Test void sameOriginBrowserGetsAnEphemeralTokenForProtectedApi() {
        given().get("/api/incidents").then().statusCode(401);
        String token = given().header("Origin", origin()).header("Sec-Fetch-Site", "same-origin")
            .header("X-Runway-Local", "1").post("/local-session").then().statusCode(200)
            .header("Cache-Control", containsString("no-store"))
            .body(not(containsString("test-presenter-key")))
            .extract().path("token");
        given().header("Authorization", "Bearer " + token).get("/api/incidents").then().statusCode(200);
        given().header("Authorization", "Bearer wrong-session-token-with-32-characters")
            .get("/api/incidents").then().statusCode(401);
    }

    @Test void crossOriginAndSimpleRequestsCannotObtainSessions() {
        for (String origin : new String[]{"https://attacker.example", "null", "http://localhost:9999"}) {
            given().header("Origin", origin).header("Sec-Fetch-Site", "same-origin")
                .header("X-Runway-Local", "1").post("/local-session").then().statusCode(403);
        }
        given().header("Origin", origin()).header("Sec-Fetch-Site", "same-origin")
            .post("/local-session").then().statusCode(403);
        given().header("Origin", origin()).header("Sec-Fetch-Site", "cross-site")
            .header("X-Runway-Local", "1").post("/local-session").then().statusCode(403);
        given().header("X-Runway-Local", "1").post("/local-session").then().statusCode(403);
        given().get("/local-session").then().statusCode(405);
    }
}
