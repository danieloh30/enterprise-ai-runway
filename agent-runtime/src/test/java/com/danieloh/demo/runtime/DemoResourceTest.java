package com.danieloh.demo.runtime;
import com.danieloh.demo.shared.Database;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import org.junit.jupiter.api.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
@QuarkusTest
class DemoResourceTest {
 @InjectMock Database db; @InjectMock RunService runs; @InjectMock GatewayClient gateway;
 static final String AUTH="Bearer test-presenter-key-with-32-characters";
 @BeforeEach void setup(){reset(db,runs,gateway);}
 @Test void apiRequiresPresenterCredentialButSpaIsPublic(){
  given().get("/api/incidents").then().statusCode(401);
  given().get("/").then().statusCode(200).body(containsString("Cleared for takeoff"));
 }
 @Test void rejectsBlankMissionAndInvalidModeBeforeStartingRun(){
  given().header("Authorization",AUTH).contentType("application/json").body("{\"incidentId\":\"INC-2042\",\"mode\":\"pretend\",\"prompt\":\"\"}")
   .post("/api/runs").then().statusCode(400);verifyNoInteractions(runs);
 }
 @Test void acceptedRunReturnsStableLocation(){
  UUID id=UUID.randomUUID();when(runs.start("INC-2042","rehearsal","Investigate")).thenReturn(id);
  given().header("Authorization",AUTH).contentType("application/json").body("{\"incidentId\":\"INC-2042\",\"mode\":\"rehearsal\",\"prompt\":\"Investigate\"}")
   .post("/api/runs").then().statusCode(202).header("Location","/api/runs/"+id).body("id",is(id.toString()));
 }
 @Test void missingModelCredentialRejectsLiveBeforeStartingRun(){
  given().header("Authorization",AUTH).contentType("application/json")
   .body(Map.of("incidentId","INC-2042","mode","live","prompt","Investigate"))
   .post("/api/runs").then().statusCode(503).body("error",containsString("OPENAI_API_KEY"));
  verifyNoInteractions(runs);
 }
 @Test void blueprintDeclaresTemplateOriginAndDoesNotEchoSecrets(){
  given().header("Authorization",AUTH).contentType("application/json").body(Map.of("prompt","Build a safe incident agent"))
   .post("/api/blueprint").then().statusCode(200).body("generator",containsString("templates"))
   .body("bobPrompt",containsString("human approval")).body(not(containsString("test-presenter-key")));
 }
 @Test void unknownProbeIsRejected(){
  given().header("Authorization",AUTH).contentType("application/json").post("/api/probes/arbitrary").then().statusCode(400);
  verifyNoInteractions(gateway);
 }
}
