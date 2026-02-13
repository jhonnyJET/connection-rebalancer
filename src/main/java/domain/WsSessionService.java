package domain;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import infrastructure.resources.rest.client.ConsulClient;
import infrastructure.resources.rest.dto.ConsulService;
import io.quarkus.logging.Log;

@ApplicationScoped
public class WsSessionService {

    @ConfigProperty(name = "ws.session.max.sessions", defaultValue = "10")
    Integer MAX_SESSIONS_PER_SERVER;
    WsSessionRepository wsSessionRepository;
    
    ConsulClient consulClient;

    public WsSessionService(WsSessionRepository wsSessionRepository, ConsulClient consulClient) {
        this.wsSessionRepository = wsSessionRepository;
        this.consulClient = consulClient;
    }

    public void sendAdminCommand(Map<String, Integer> sessions){
        wsSessionRepository.dropWsSessions(sessions);
    }

    public Map<String, PersistentSession> retrieveSessionsToDrop() {
        // Analyze sessions and determine if rebalancing is needed
        var wsSessions = findAllSessions();
        var sessionUtilizationMap = retrieveServerSessionUtilization(wsSessions);

        return Map.of(); // Placeholder return value
    }

    private Map<String, Integer> countOfSessionsPerServer(List<PersistentSession> wsSessions){
        Map<String, Integer> sessionCountMap = new java.util.HashMap<>();
        var uniqueHostIds = wsSessions.stream().map(PersistentSession::hostId).collect(Collectors.toSet());
        uniqueHostIds.stream().forEach(hostId -> {
            var count = (int) wsSessions.stream().filter(session -> session.hostId().equals(hostId)).count();
            sessionCountMap.put(hostId, count);
        });
        return sessionCountMap;
    }

    public Map<String, WsSessionUtilization> retrieveServerSessionUtilization(List<PersistentSession> wsSessions) {
        // create a map of serverId to WsSessionUtilization
        Map<String, WsSessionUtilization> utilizationMap = new java.util.HashMap<>();
        var sessionsPerHost = countOfSessionsPerServer(wsSessions);
        Logger.getLogger(WsSessionService.class.getName()).info("Sessions per host: " + sessionsPerHost.toString());
        sessionsPerHost.forEach((hostId, sessionCount) -> {
            utilizationMap.put(hostId, new WsSessionUtilization(sessionCount, MAX_SESSIONS_PER_SERVER));
        });
        // wsSessions.stream().collect(Collectors.toMap(PersistentSession::hostId, new WsSessionUtilization()));
        return utilizationMap;
    }


    public List<PersistentSession> findAllSessions() {
        return wsSessionRepository.findAllSessions();
    }

    public void dropServerSessions(String fromServerId, int numberOfSessions) {
        // Here we could add some business logic before dropping sessions with a redis admin command for listening servers...
        wsSessionRepository.dropWsSessions(Map.of(fromServerId, numberOfSessions));
    }

    private List<ConsulService> getServiceInstances(String serviceName) {
        
        var services = consulClient.getServiceInstances(serviceName);
        return services;
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
