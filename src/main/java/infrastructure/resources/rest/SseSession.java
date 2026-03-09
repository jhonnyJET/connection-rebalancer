package infrastructure.resources.rest;

import api.SseSessionApi;
import domain.SsePersistentSession;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

@Path("/sse-session")
public class SseSession {

    @Inject
    private SseSessionApi api;

    @POST
    @Path("{user}/test")
    @Consumes(MediaType.APPLICATION_JSON)
    public void save(@PathParam("user") String user) {
        try {
            // Reserved for local test payloads if needed.
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @GET
    @Path("all")
    @Produces(MediaType.APPLICATION_JSON)
    public List<SsePersistentSession> getAllSessions() {
        try {
            return api.findAllSessions();
        } catch (Exception e) {
        }

        return List.of();
    }

    @POST
    @Path("{hostId}/admin/command")
    @Produces(MediaType.APPLICATION_JSON)
    public void dropSessions(@PathParam("hostId") String hostId) {
        api.sendAdminCommand(Map.of(hostId, 1));
    }
}
