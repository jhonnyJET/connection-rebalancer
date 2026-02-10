package infrastructure.resources.rest.client;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import infrastructure.resources.rest.dto.ConsulService;

@RegisterRestClient(configKey = "consul-server")
public interface ConsulClient {
    @PUT
    @Path("/v1/agent/service/maintenance/{serviceId}")
    void toggleService(@PathParam("serviceId") String serviceId, @QueryParam("enable") String enable, @QueryParam("reason") String reason);
    
    @GET
    @Path("/v1/health/service/{serviceName}")
    List<ConsulService> getServiceInstances(@PathParam("serviceName") String serviceName);
}
