package com.danieloh.demo.runtime.api;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.*;
import java.util.Map;
@Provider
public class ServiceErrorMapper implements ExceptionMapper<IllegalStateException> {
    @Override public Response toResponse(IllegalStateException e) {
        return Response.status(503).entity(Map.of("error","A required service is unavailable. Check ./demo.sh status and service logs.")).build();
    }
}
