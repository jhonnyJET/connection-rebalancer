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
public class SseSessionService {

    @ConfigProperty(name = "sse.session.max.sessions", defaultValue = "10")
    Integer maxSessionsPerServer;

    SseSessionRepository sseSessionRepository;
    ConsulClient consulClient;

    public SseSessionService(SseSessionRepository sseSessionRepository, ConsulClient consulClient) {
        this.sseSessionRepository = sseSessionRepository;
        this.consulClient = consulClient;
    }

    public void sendAdminCommand(Map<String, Integer> sessions) {
        sseSessionRepository.dropSseSessions(sessions);
    }

    private Map<String, Integer> countOfSessionsPerServer(List<SsePersistentSession> sseSessions) {
        Map<String, Integer> sessionCountMap = new java.util.HashMap<>();
        var uniqueHostIds = sseSessions.stream().map(SsePersistentSession::hostId).collect(Collectors.toSet());
        uniqueHostIds.forEach(hostId -> {
            var count = (int) sseSessions.stream().filter(session -> session.hostId().equals(hostId)).count();
            sessionCountMap.put(hostId, count);
        });
        return sessionCountMap;
    }

    public Map<String, SseSessionUtilization> retrieveServerSessionUtilization(List<SsePersistentSession> sseSessions) {
        Map<String, SseSessionUtilization> utilizationMap = new java.util.HashMap<>();
        var sessionsPerHost = countOfSessionsPerServer(sseSessions);
        Logger.getLogger(SseSessionService.class.getName()).info("SSE sessions per host: " + sessionsPerHost);
        sessionsPerHost.forEach((hostId, sessionCount) -> {
            utilizationMap.put(hostId, new SseSessionUtilization(sessionCount, maxSessionsPerServer));
        });
        return utilizationMap;
    }

    public List<SsePersistentSession> findAllSessions() {
        return sseSessionRepository.findAllSessions();
    }

    public void dropServerSessions(String fromServerId, int numberOfSessions) {
        sseSessionRepository.dropSseSessions(Map.of(fromServerId, numberOfSessions));
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
