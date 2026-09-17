package com.danieloh.demo.gateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.danieloh.demo.shared.Secrets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GatewayPolicyTest {
 final ObjectMapper mapper=new ObjectMapper(); final GatewayPolicy policy=new GatewayPolicy();
 @Test void allowsBoundedReadAndRejectsWriteEscalation() throws Exception {
  var read=mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_incident\",\"arguments\":{\"incidentId\":\"INC-2042\"}}}");
  assertEquals(200,policy.evaluate(read,false).status());
  assertEquals(403,policy.evaluate(read,true).status());
  var write=mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"create_followup\",\"arguments\":{\"runId\":\"01234567-1234-1234-1234-012345678901\"}}}");
  assertEquals(403,policy.evaluate(write,false).status());assertEquals(200,policy.evaluate(write,true).status());
 }
 @Test void rejectsBatchesUnknownMethodsAndInjectionArguments() throws Exception {
  for(String body:new String[]{"[]","null","{\"jsonrpc\":\"1.0\",\"method\":\"ping\"}"}) assertEquals(400,policy.evaluate(mapper.readTree(body),false).status());
  assertEquals(403,policy.evaluate(mapper.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"roots/list\"}"),false).status());
  var node=mapper.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"get_incident\",\"arguments\":{\"incidentId\":\"INC-2042' OR 1=1\"}}}");
  assertEquals(400,policy.evaluate(node,false).status());
 }
 @Test void hidesWriteToolsInBothJsonAndSse() throws Exception {
  var gateway=new GatewayResource();gateway.mapper=mapper;
  String payload="{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[{\"name\":\"get_incident\"},{\"name\":\"create_followup\"}]}}";
  assertFalse(gateway.filterTools(payload,false,"application/json").contains("create_followup"));
  String sse=gateway.filterTools("event: message\ndata: "+payload+"\n\n",false,"text/event-stream");
  assertTrue(sse.contains("get_incident"));assertFalse(sse.contains("create_followup"));
  assertFalse(gateway.filterTools(payload,true,"application/json").contains("get_incident"));
 }
 @Test void rateLimiterActuallyDeniesOverBudget() {
  var window=new GatewayResource.Window();assertTrue(window.take(2));assertTrue(window.take(2));assertFalse(window.take(2));
 }
 @Test void credentialComparisonRejectsMissingShortAndWrongSecrets() {
  assertFalse(Secrets.matches(null,"a".repeat(32)));assertFalse(Secrets.matches("short","short"));
  assertFalse(Secrets.matches("a".repeat(32),"b".repeat(32)));assertTrue(Secrets.matches("a".repeat(32),"a".repeat(32)));
 }
}
