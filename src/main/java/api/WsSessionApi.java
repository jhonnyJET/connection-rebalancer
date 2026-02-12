package api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import domain.PersistentSession;
import domain.WsSessionService;
import domain.WsSessionUtilization;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import domain.utils.AutoScaler;
import infrastructure.resources.rest.dto.ConsulService;

@ApplicationScoped
public class WsSessionApi {
    WsSessionService wsSessionService;

    ObjectMapper objectMapper;

    @ConfigProperty(name = "connection.limit.per.host")
    Integer MAX_SESSIONS_PER_SERVER;

    @ConfigProperty(name = "overutilized.tolerance.percent")
    Integer OVERUTILIZED_TOLERANCE_PERCENT;

    @ConfigProperty(name = "underutilized.tolerance.percent")
    Integer UNDERUTILIZED_TOLERANCE_PERCENT;

    @ConfigProperty(name = "max.utilization.percent")
    Integer MAX_UTILIZATION_PERCENT;

    @ConfigProperty(name = "min.utilization.percent")
    Integer MIN_UTILIZATION_PERCENT;

    @Inject
    AutoScaler autoScaler;

    public WsSessionApi(ObjectMapper objectMapper, WsSessionService wsSessionService) {
        this.objectMapper = objectMapper;
        this.wsSessionService = wsSessionService;
    }

    public List<PersistentSession> findAllSessions() throws JsonProcessingException {
        var wsSessions = wsSessionService.findAllSessions();
        objectMapper.writeValueAsString(wsSessions);
        return wsSessions;
    }

    public void sendAdminCommand(Map<String, Integer> sessions) {
        wsSessionService.sendAdminCommand(sessions);
    }

    public void analyzeSessionServerUtilization() {
        var wsSessions = wsSessionService.findAllSessions();
        var sessionUtilizationMap = wsSessionService.retrieveServerSessionUtilization(wsSessions);
        var consulActiveServices = wsSessionService.getConsulActiveServices("ws-app");
        var consulInactiveServices = wsSessionService.getConsulInactiveServices("ws-app");
        try {
            Logger.getAnonymousLogger().log(Level.INFO, "Found Sessions" + objectMapper.writeValueAsString(wsSessions));
            Logger.getAnonymousLogger().log(Level.INFO, "Utilization" + objectMapper.writeValueAsString(sessionUtilizationMap));
        } catch (Exception e){
            Logger.getAnonymousLogger().log(Level.INFO, e.getMessage());
        }
        if (wsSessions.isEmpty()) {
            Logger.getAnonymousLogger().log(Level.INFO, "No Sessions to analyze");
            return;
        }        

        var overrallActiveSessions = sessionUtilizationMap.values().stream()
                .mapToInt(WsSessionUtilization::activeSessions)
                .sum();

        var overrallMaxSessions = consulActiveServices.size() * MAX_SESSIONS_PER_SERVER;

        Map<String, Integer> utilizationMapPercentMap = sessionUtilizationMap.entrySet().stream()
               .map(p -> {
                Logger.getAnonymousLogger().log(Level.INFO, "Calculating utilization for server " + p.getKey() + ": " + p.getValue().activeSessions() + "/" + p.getValue().maxSessions());
                Logger.getAnonymousLogger().log(Level.INFO, "Utilization percent: " + ((float) p.getValue().activeSessions() / p.getValue().maxSessions()) * 100);
                return Map.of(p.getKey(), (int) (((float)p.getValue().activeSessions() / p.getValue().maxSessions()) * 100));
               })
               .flatMap(m -> m.entrySet().stream())
               .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Logger.getAnonymousLogger().log(Level.INFO, "Utilization Percent Map: " + utilizationMapPercentMap.toString() + " overallActiveSessions: " + overrallActiveSessions + " overrallMaxSessions: " + overrallMaxSessions);

        if (overrallMaxSessions == 0) {
            Logger.getAnonymousLogger().log(Level.INFO, "No Max Sessions configured, cannot analyze balance");
            return;
        }

        var overallUtilizationPercent = (((float)overrallActiveSessions / overrallMaxSessions) * 100);
        var numberOfServersToScaleOut = 0;
        var numberOfServersToScaleIn = 0;

        Logger.getAnonymousLogger().log(Level.INFO, "Overall Utilization Percent Scale: " + overallUtilizationPercent + "%");
        if (overallUtilizationPercent > MAX_UTILIZATION_PERCENT) {            
            var maxTargetServerTreshold = Math.ceil(overrallActiveSessions / ((float)MAX_SESSIONS_PER_SERVER * ((float)MAX_UTILIZATION_PERCENT / 100.0)));
            System.out.println("Warning: Overall WebSocket session utilization is above 70%: " + overallUtilizationPercent + "%");
            numberOfServersToScaleOut = (int)maxTargetServerTreshold - consulActiveServices.size();
            var targetServerCount = consulActiveServices.size() + (int)numberOfServersToScaleOut;
            Logger.getAnonymousLogger().log(Level.INFO, "Number of servers to scale out: " + numberOfServersToScaleOut);
            scaleOutSessionServers(targetServerCount, numberOfServersToScaleOut, consulInactiveServices);
        }

        if (overallUtilizationPercent < MIN_UTILIZATION_PERCENT) {
            if(sessionUtilizationMap.size() <= 0){
                return;
            }
            var averageTargetServerTreshold = Math.ceil(overrallActiveSessions / ((float)MAX_SESSIONS_PER_SERVER * ((float)MAX_UTILIZATION_PERCENT / 100.0)));
            Logger.getAnonymousLogger().log(Level.INFO, "Average Target Server Treshold: " + averageTargetServerTreshold);
            numberOfServersToScaleIn = (int)consulActiveServices.size() - (int)averageTargetServerTreshold;
            Logger.getAnonymousLogger().log(Level.INFO, "Number of servers to scale in: " + numberOfServersToScaleIn);
            if(numberOfServersToScaleIn > 0) {
                scaleInSessionServers((int)numberOfServersToScaleIn, utilizationMapPercentMap);
            }
        }

        if(numberOfServersToScaleIn == 0 && numberOfServersToScaleOut == 0) {
            killServersWithNoSessions(utilizationMapPercentMap, consulActiveServices);
        }
    }

    public void killServersWithNoSessions(Map<String, Integer> utilizationMapPercentMap, List<ConsulService> consulActiveServices) {
        var consulInactiveServices = wsSessionService.getConsulInactiveServices("ws-app");
        
        consulInactiveServices.forEach(service -> {
            var isThereAnySessionConnectedToInactiveService = utilizationMapPercentMap.containsKey(service.Service.Address) && utilizationMapPercentMap.get(service.Service.Address) > 0;
            if (!isThereAnySessionConnectedToInactiveService) {
                System.out.println("Info: Terminating inactive service " + service.Service.ID + " at " + service.Service.Address + " with no active sessions.");
                autoScaler.stopSpecificContainer(service.Service.Address);
            }
        });
    }       

    public void analyzeSessionServerBalance() {
        var wsSessions = wsSessionService.findAllSessions();
        var wsSessionUtilizationMap = wsSessionService.retrieveServerSessionUtilization(wsSessions);
        var consulActiveServices = wsSessionService.getConsulActiveServices("ws-app");
        try {
            Logger.getAnonymousLogger().log(Level.INFO, "Found Sessions" + objectMapper.writeValueAsString(wsSessions));
            Logger.getAnonymousLogger().log(Level.INFO, "Utilization" + objectMapper.writeValueAsString(wsSessionUtilizationMap));
        } catch (Exception e){
            Logger.getAnonymousLogger().log(Level.INFO, e.getMessage());
        }
        if (wsSessions.isEmpty()) {
            Logger.getAnonymousLogger().log(Level.INFO, "No Sessions to rebalance");
            return;
        }
       var overrallActiveSessions = wsSessionUtilizationMap.values().stream()
                                                         .mapToInt(WsSessionUtilization::activeSessions)
                                                         .sum();

       var overrallMaxSessions = consulActiveServices.size() * MAX_SESSIONS_PER_SERVER;

        if (overrallMaxSessions == 0) {
            Logger.getAnonymousLogger().log(Level.INFO, "No Max Sessions configured, cannot analyze balance");
            return;
        }

       Logger.getAnonymousLogger().log(Level.INFO, "Overall Active Sessions: " + overrallActiveSessions);
       Logger.getAnonymousLogger().log(Level.INFO, "Overall Max Sessions: " + overrallMaxSessions);
       var overrallUtilizationPercent = (int)((float)overrallActiveSessions / overrallMaxSessions * 100);
       Logger.getAnonymousLogger().log(Level.INFO, "Overall Utilization Percent: " + overrallUtilizationPercent + "%");
      // Placeholder for balance analysis logic
       System.out.println("Analyzing WebSocket session server balance...");

       Map<String, Integer> underUtilizedServers = new HashMap<>();
       Map<String, Integer> overUtilizedServers = new HashMap<>();
       Map<String, Integer> utilizationMapPercentMap = wsSessionUtilizationMap.entrySet().stream()
               .map(p -> {
                Logger.getAnonymousLogger().log(Level.INFO, "Calculating utilization for server " + p.getKey() + ": " + p.getValue().activeSessions() + "/" + p.getValue().maxSessions());
                Logger.getAnonymousLogger().log(Level.INFO, "Utilization percent: " + ((float) p.getValue().activeSessions() / p.getValue().maxSessions()) * 100);
                return Map.of(p.getKey(), (int) (((float)p.getValue().activeSessions() / p.getValue().maxSessions()) * 100));
               })
               .flatMap(m -> m.entrySet().stream())
               .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        var activeConsulServicesWithNoSessions = consulActiveServices.stream()
            .filter(service -> !utilizationMapPercentMap.containsKey(service.Service.Address) || utilizationMapPercentMap.get(service.Service.Address) == 0)
            .toList();

        activeConsulServicesWithNoSessions.forEach(service -> utilizationMapPercentMap.put(service.Service.Address, 0));
        Logger.getAnonymousLogger().log(Level.INFO, "Utilization Percent Map: " + utilizationMapPercentMap.toString());

       utilizationMapPercentMap.keySet().forEach(server -> {
        Logger.getAnonymousLogger().log(Level.INFO, "Evaluating server " + server + " with utilization " + utilizationMapPercentMap.get(server) + "% against overall utilization " + overrallUtilizationPercent + "%");
           if(utilizationMapPercentMap.get(server) > (overrallUtilizationPercent + OVERUTILIZED_TOLERANCE_PERCENT))
               overUtilizedServers.put(server, utilizationMapPercentMap.get(server));
           else if(utilizationMapPercentMap.get(server) < overrallUtilizationPercent)
               underUtilizedServers.put(server, utilizationMapPercentMap.get(server));
       });

       var sortedOverUtilizedServers = overUtilizedServers.entrySet().stream()
               .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
               .toList();

       var sortedUnderUtilizedServers = underUtilizedServers.entrySet().stream()
               .sorted(Comparator.comparingInt(Map.Entry::getValue))
               .toList();

      Logger.getAnonymousLogger().log(Level.INFO, "Overutilized Servers: " + sortedOverUtilizedServers.toString());
      Logger.getAnonymousLogger().log(Level.INFO, "Underutilized Servers: " + sortedUnderUtilizedServers.toString());

       sortedOverUtilizedServers.forEach(s -> {
          double maxSessions = wsSessionUtilizationMap.get(s.getKey()).maxSessions();
          double activeSessions = wsSessionUtilizationMap.get(s.getKey()).activeSessions();
          double activeSessionAverageTreshold = Math.ceil((maxSessions * ((double)overrallUtilizationPercent/100)));
          Logger.getAnonymousLogger().log(Level.INFO, "Server " + s.getKey() + " OverallUtilization:" + overrallUtilizationPercent + "% has max sessions: " + maxSessions + " and active session average treshold: " + activeSessionAverageTreshold);
          int numberOfsessionsToOffload= (int)(activeSessions - activeSessionAverageTreshold);
          Logger.getAnonymousLogger().log(Level.INFO, "Offloading " + numberOfsessionsToOffload + " sessions from server " + s.getKey());
          offLoadSessions(s.getKey(), numberOfsessionsToOffload);
       });
    }

    private void offLoadSessions(String fromServerId, int numberOfSessions) {
        wsSessionService.dropServerSessions(fromServerId, numberOfSessions);
        // Placeholder for offloading sessions logic
        //        System.out.println("Offloading " + numberOfSessions + " sessions from server " + fromServerId + " to server " + fromServerId)
        // Send redis admin command to drop sessions on fromServerId
    }

    public void scaleInSessionServers(int numberOfServers, Map<String, Integer> serverUtilization) {
        // Placeholder for scaling in logic
        System.out.println("Scaling in WebSocket session servers...");
        autoScaler.scaleIn(numberOfServers, serverUtilization);
        // Send command to docker or k8s orchestrator to cordon/remove server from load balancer
        // NOTE: Cordon server with the least number of active sessions
    }

    public void scaleOutSessionServers(int targetServerCount, int numberOfServersToScaleOut, List<ConsulService> consulInactiveServices) {
        // Placeholder for scaling out logic
        var inactiveServicesCount = consulInactiveServices.size();
        try {
            Logger.getAnonymousLogger().log(Level.INFO, "Consul Inactive Services: " + objectMapper.writeValueAsString(consulInactiveServices));
        } catch (Exception e) {
            Logger.getAnonymousLogger().log(Level.INFO, e.getMessage());
        }
        Logger.getAnonymousLogger().log(Level.INFO, "Inactive services count: " + inactiveServicesCount + ", Target server count: " + targetServerCount + " Number of servers to scale out: " + numberOfServersToScaleOut);
        if (inactiveServicesCount >= numberOfServersToScaleOut) {
            System.out.println("There are enough inactive services to handle the scale out request. Activating inactive services...");
            consulInactiveServices.stream()
                .limit(numberOfServersToScaleOut)
                .forEach(service -> {
                    System.out.println("Activating inactive service " + service.Service.ID + " at " + service.Service.Address);
                    wsSessionService.toggleConsulService(service.Service.ID, "false", "Activating service due to scale out request");
                });
        }
        var serversToScaleOut = targetServerCount - inactiveServicesCount;        
        if(serversToScaleOut <= 0){
            System.out.println("No need to scale out, inactive services can handle the target server count.");
            return;
        } 
        System.out.println("Scaling out WebSocket session servers...");
        autoScaler.scaleOut(serversToScaleOut);
    }
}
