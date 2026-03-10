package domain;

import infrastructure.resources.rest.client.ConsulClient;
import infrastructure.resources.rest.dto.ConsulService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GrpcSessionService {

    @ConfigProperty(name = "grpc.session.max.sessions", defaultValue = "10")
    Integer maxSessionsPerServer;

    GrpcSessionRepository grpcSessionRepository;
    ConsulClient consulClient;

    public GrpcSessionService(GrpcSessionRepository grpcSessionRepository, ConsulClient consulClient) {
        this.grpcSessionRepository = grpcSessionRepository;
        this.consulClient = consulClient;
    }

    public void sendAdminCommand(Map<String, Integer> sessions) {
        grpcSessionRepository.dropGrpcSessions(sessions);
    }

    private Map<String, Integer> countOfSessionsPerServer(List<GrpcPersistentSession> grpcSessions) {
        Map<String, Integer> sessionCountMap = new java.util.HashMap<>();
        var uniqueHostIds = grpcSessions.stream().map(GrpcPersistentSession::hostId).collect(Collectors.toSet());
        uniqueHostIds.forEach(hostId -> {
            var count = (int) grpcSessions.stream().filter(session -> session.hostId().equals(hostId)).count();
            sessionCountMap.put(hostId, count);
        });
        return sessionCountMap;
    }

    public Map<String, GrpcSessionUtilization> retrieveServerSessionUtilization(List<GrpcPersistentSession> grpcSessions) {
        Map<String, GrpcSessionUtilization> utilizationMap = new java.util.HashMap<>();
        var sessionsPerHost = countOfSessionsPerServer(grpcSessions);
        Logger.getLogger(GrpcSessionService.class.getName()).info("gRPC sessions per host: " + sessionsPerHost);
        sessionsPerHost.forEach((hostId, sessionCount) -> {
            utilizationMap.put(hostId, new GrpcSessionUtilization(sessionCount, maxSessionsPerServer));
        });
        return utilizationMap;
    }

    public List<GrpcPersistentSession> findAllSessions() {
        return grpcSessionRepository.findAllSessions();
    }

    public void dropServerSessions(String fromServerId, int numberOfSessions) {
        grpcSessionRepository.dropGrpcSessions(Map.of(fromServerId, numberOfSessions));
    }

    private List<ConsulService> getServiceInstances(String serviceName) {
        return consulClient.getServiceInstances(serviceName);
    }

    public List<ConsulService> getConsulInactiveServices(String serviceName) {
        return getServiceInstances(serviceName)
                .stream()
                .filter(s -> s.Checks.stream().anyMatch(c -> c.CheckID.contains("_service_maintenance")))
                .toList();
    }

    public List<ConsulService> getConsulActiveServices(String serviceName) {
        return getServiceInstances(serviceName)
                .stream()
                .filter(s -> s.Checks.stream().allMatch(c -> c.Status.equals("passing")))
                .toList();
    }

    public void toggleConsulService(String serviceId, String enable, String reason) {
        consulClient.toggleService(serviceId, enable, reason);
    }
}
