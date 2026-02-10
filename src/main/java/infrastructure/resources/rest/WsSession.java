package infrastructure.resources.rest;


import api.WsSessionApi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

import domain.PersistentSession;

@Path("/ws-session")
public class WsSession {

    @Inject
    private WsSessionApi api;

    @POST
    @Path("{user}/test")
    @Consumes(MediaType.APPLICATION_JSON)
    public void save(@PathParam("user") String user) {
        try {
            // api.save(Map.<String,PersistentSession>of(UUID.randomUUID().toString(), new PersistentSession(user, UUID.randomUUID().toString(), UUID.randomUUID().toString())));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @GET
    @Path("all")
    @Produces(MediaType.APPLICATION_JSON)
    public List<PersistentSession> getAllSessions() {
        try {
            return api.findAllSessions();
        }
        catch (Exception e) {}

        return List.of();
    }

    @POST
    @Path("{hostId}/admin/command")
    @Produces(MediaType.APPLICATION_JSON)
    public void dropSessions(@PathParam("hostId") String hostId) {
        api.sendAdminCommand(Map.of(hostId, 1));
    }


}
