package infrastructure.resources.rest;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import infrastructure.resources.rest.client.ConsulClient;
import infrastructure.resources.rest.dto.ConsulService;

@ApplicationScoped
public class RestConsulClient implements ConsulClient {

    private final ConsulClient consulClient;

    public RestConsulClient(@RestClient ConsulClient consulClient) {
        this.consulClient = consulClient;
    }

    // public RestTracker(@RestClient Tracker tracker, MeterRegistry registry) {
    //     this.tracker = tracker;
    //     this.registry = registry;
    //     this.messageLatencyTimer = Timer.builder("tracker.rest.message.latency.return.leg")
    //                                     .description("Latency of REST messages")
    //                                     .register(registry);
    // }

    @Override
    public void toggleService(String serviceId, String enable, String reason) {
        consulClient.toggleService(serviceId, enable, reason);
    }

    @Override
    public List<ConsulService> getServiceInstances(String serviceName) {
        return consulClient.getServiceInstances(serviceName);
    }
}
