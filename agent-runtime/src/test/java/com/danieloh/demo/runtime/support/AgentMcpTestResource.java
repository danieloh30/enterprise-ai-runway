package com.danieloh.demo.runtime.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class AgentMcpTestResource implements QuarkusTestResourceLifecycleManager {
    private static final List<String> calls=new CopyOnWriteArrayList<>();
    private HttpServer server;

    public static List<String> calls() {return List.copyOf(calls);}
    public static void resetCalls() {calls.clear();}

    @Override
    public Map<String,String> start() {
        try {
            var mapper=new ObjectMapper();
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/mcp",exchange -> {
                try {
                    var request=mapper.readTree(exchange.getRequestBody());
                    if(!request.has("id")) {exchange.sendResponseHeaders(202,-1);return;}
                    Object result=switch(request.path("method").asText()) {
                        case "initialize" -> Map.of("protocolVersion","2025-06-18","capabilities",Map.of("tools",Map.of()),
                            "serverInfo",Map.of("name","test-enterprise","version","1.0"));
                        case "tools/list" -> Map.of("tools",List.of(Map.of("name","get_incident","description","Read an incident",
                            "inputSchema",Map.of("type","object","properties",Map.of("incidentId",Map.of("type","string")),
                                "required",List.of("incidentId")))));
                        case "tools/call" -> {
                            calls.add(request.path("params").path("name").asText());
                            yield Map.of("content",List.of(Map.of("type","text","text","Observed incident: latency 850ms")),"isError",false);
                        }
                        default -> Map.of();
                    };
                    byte[] body=mapper.writeValueAsBytes(Map.of("jsonrpc","2.0","id",request.get("id"),"result",result));
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,body.length);
                    exchange.getResponseBody().write(body);
                } finally {exchange.close();}
            });
            server.start();
            return Map.of("quarkus.langchain4j.mcp.enterprise.url","http://127.0.0.1:"+server.getAddress().getPort()+"/mcp");
        } catch(Exception e) {throw new IllegalStateException(e);}
    }

    @Override
    public void stop() {if(server!=null) server.stop(0);}
}
