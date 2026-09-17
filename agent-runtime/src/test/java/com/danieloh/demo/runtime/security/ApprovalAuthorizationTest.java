package com.danieloh.demo.runtime.security;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ApprovalAuthorizationTest {
 @Test void oidcPresenterCannotApproveWithoutApproverRole(){
  var auth=new ApiAuthentication();auth.oidc=true;auth.identity=mock(SecurityIdentity.class);
  when(auth.identity.hasRole("presenter")).thenReturn(true);
  var request=mock(ContainerRequestContext.class);var uri=mock(UriInfo.class);
  when(request.getUriInfo()).thenReturn(uri);when(uri.getPath()).thenReturn("/api/runs/abc/approve");
  auth.filter(request);var response=ArgumentCaptor.forClass(Response.class);verify(request).abortWith(response.capture());assertEquals(403,response.getValue().getStatus());
 }
 @Test void oidcApproverWithPresenterRoleCanDecide(){
  var auth=new ApiAuthentication();auth.oidc=true;auth.identity=mock(SecurityIdentity.class);
  when(auth.identity.hasRole("presenter")).thenReturn(true);when(auth.identity.hasRole("approver")).thenReturn(true);
  var request=mock(ContainerRequestContext.class);var uri=mock(UriInfo.class);
  when(request.getUriInfo()).thenReturn(uri);when(uri.getPath()).thenReturn("/api/runs/abc/approve");
  auth.filter(request);verify(request,never()).abortWith(any());
 }
}
