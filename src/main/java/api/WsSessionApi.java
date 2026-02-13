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

    private static Logger logger = Logger.getLogger(WsSessionApi.class.getName());
    
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
        if (wsSessions.isEmpty() && consulActiveServices.size() <= 1) {
            logger.log(Level.INFO, "No Sessions to analyze");
            return;
        }        

        var overrallActiveSessions = sessionUtilizationMap.values().stream()
                .mapToInt(WsSessionUtilization::activeSessions)
                .sum();

        var overrallMaxSessions = consulActiveServices.size() * MAX_SESSIONS_PER_SERVER;

        Map<String, Integer> utilizationMapPercentMap = sessionUtilizationMap.entrySet().stream()
               .map(p -> {
                return Map.of(p.getKey(), (int) (((float)p.getValue().activeSessions() / p.getValue().maxSessions()) * 100));
               })
               .flatMap(m -> m.entrySet().stream())
               .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        

        if (overrallMaxSessions == 0) {
            logger.log(Level.INFO, "No Max Sessions configured, cannot analyze balance");
            return;
        }

        var overallUtilizationPercent = (((float)overrallActiveSessions / overrallMaxSessions) * 100);
        var numberOfServersToScaleOut = 0;
        var numberOfServersToScaleIn = 0;

        if (overallUtilizationPercent > MAX_UTILIZATION_PERCENT) {            
            var maxTargetServerTreshold = Math.ceil(overrallActiveSessions / ((float)MAX_SESSIONS_PER_SERVER * ((float)MAX_UTILIZATION_PERCENT / 100.0)));
            numberOfServersToScaleOut = (int)maxTargetServerTreshold - consulActiveServices.size();
            var targetServerCount = consulActiveServices.size() + (int)numberOfServersToScaleOut;            
            scaleOutSessionServers(targetServerCount, numberOfServersToScaleOut, consulInactiveServices);
        }

        if (overallUtilizationPercent < MIN_UTILIZATION_PERCENT) {
            if(sessionUtilizationMap.size() <= 0 && consulActiveServices.size() <= 1){
                return;
            }

            if(sessionUtilizationMap.size() <= 0 && consulActiveServices.size() > 1) {
                numberOfServersToScaleIn = consulActiveServices.size() - 1;
            } else {
                var averageTargetServerTreshold = Math.ceil(overrallActiveSessions / ((float)MAX_SESSIONS_PER_SERVER * ((float)MAX_UTILIZATION_PERCENT / 100.0)));                
                numberOfServersToScaleIn = (int)consulActiveServices.size() - (int)averageTargetServerTreshold;                
            }

            if(numberOfServersToScaleIn > 0) {
                scaleInSessionServers((int)numberOfServersToScaleIn, utilizationMapPercentMap);
            }
        }

        
        logger.info("Summary: " + 
                    "Overall Active Sessions: " + overrallActiveSessions + 
                    ", Overall Max Sessions: " + overrallMaxSessions + 
                    ", Overall Utilization Percent: " + overallUtilizationPercent + 
                    "%, Number of servers to scale out: " + numberOfServersToScaleOut + 
                    ", Number of servers to scale in: " + numberOfServersToScaleIn);     
        logger.info("Utilization Percent Map: " + utilizationMapPercentMap.toString());

        if(numberOfServersToScaleIn == 0 && numberOfServersToScaleOut == 0) {
            killServersWithNoSessions(utilizationMapPercentMap, consulActiveServices);
        }
    }

    public void killServersWithNoSessions(Map<String, Integer> utilizationMapPercentMap, List<ConsulService> consulActiveServices) {
        var consulInactiveServices = wsSessionService.getConsulInactiveServices("ws-app");
        
        consulInactiveServices.forEach(service -> {
            var isThereAnySessionConnectedToInactiveService = utilizationMapPercentMap.containsKey(service.Service.Address) && utilizationMapPercentMap.get(service.Service.Address) > 0;
            if (!isThereAnySessionConnectedToInactiveService) {
                autoScaler.stopSpecificContainer(service.Service.Address);
            }
        });
    }       

    public void analyzeSessionServerBalance() {
        var wsSessions = wsSessionService.findAllSessions();
        var wsSessionUtilizationMap = wsSessionService.retrieveServerSessionUtilization(wsSessions);
        var consulActiveServices = wsSessionService.getConsulActiveServices("ws-app");
        // try {
        //     Logger.getAnonymousLogger().log(Level.INFO, "Found Sessions" + objectMapper.writeValueAsString(wsSessions));
        //     Logger.getAnonymousLogger().log(Level.INFO, "Utilization" + objectMapper.writeValueAsString(wsSessionUtilizationMap));
        // } catch (Exception e){
        //     Logger.getAnonymousLogger().log(Level.INFO, e.getMessage());
        // }
        if (wsSessions.isEmpty()) {
            logger.info("No Sessions to rebalance");
            return;
        }
       var overrallActiveSessions = wsSessionUtilizationMap.values().stream()
                                                         .mapToInt(WsSessionUtilization::activeSessions)
                                                         .sum();

       var overrallMaxSessions = consulActiveServices.size() * MAX_SESSIONS_PER_SERVER;

        if (overrallMaxSessions == 0) {
            logger.info("No Max Sessions configured, cannot analyze balance");
            return;
        }

       var overrallUtilizationPercent = (int)((float)overrallActiveSessions / overrallMaxSessions * 100);

       Map<String, Integer> underUtilizedServers = new HashMap<>();
       Map<String, Integer> overUtilizedServers = new HashMap<>();
       Map<String, Integer> utilizationMapPercentMap = wsSessionUtilizationMap.entrySet().stream()
               .map(p -> {               
                return Map.of(p.getKey(), (int) (((float)p.getValue().activeSessions() / p.getValue().maxSessions()) * 100));
               })
               .flatMap(m -> m.entrySet().stream())
               .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        var activeConsulServicesWithNoSessions = consulActiveServices.stream()
            .filter(service -> !utilizationMapPercentMap.containsKey(service.Service.Address) || utilizationMapPercentMap.get(service.Service.Address) == 0)
            .toList();

        activeConsulServicesWithNoSessions.forEach(service -> utilizationMapPercentMap.put(service.Service.Address, 0));        

       utilizationMapPercentMap.keySet().forEach(server -> {
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

      logger.info("Rebalancing Summary:  Overutilized Servers: " + overUtilizedServers.toString() + 
                  ", Underutilized Servers: " + underUtilizedServers.toString() + 
                  ", Overall Utilization Percent: " + overrallUtilizationPercent + "%, " +
                  "Overall Active Sessions: " + overrallActiveSessions);
      logger.info("Utilization Percent Map: " + utilizationMapPercentMap.toString());

       sortedOverUtilizedServers.forEach(s -> {
          double maxSessions = wsSessionUtilizationMap.get(s.getKey()).maxSessions();
          double activeSessions = wsSessionUtilizationMap.get(s.getKey()).activeSessions();
          double activeSessionAverageTreshold = Math.ceil((maxSessions * ((double)overrallUtilizationPercent/100)));
          int numberOfsessionsToOffload= (int)(activeSessions - activeSessionAverageTreshold);        
          offLoadSessions(s.getKey(), numberOfsessionsToOffload);
       });
    }

    private void offLoadSessions(String fromServerId, int numberOfSessions) {
        logger.info("Offloading " + numberOfSessions + " sessions from server " + fromServerId);
        wsSessionService.dropServerSessions(fromServerId, numberOfSessions);
    }

    public void scaleInSessionServers(int numberOfServers, Map<String, Integer> serverUtilization) {
        System.out.println("Scaling in WebSocket session servers...");
        autoScaler.scaleIn(numberOfServers, serverUtilization);
        // Send command to docker or k8s orchestrator to cordon/remove server from load balancer
        // NOTE: Cordon server with the least number of active sessions
    }

    public void scaleOutSessionServers(int targetServerCount, int numberOfServersToScaleOut, List<ConsulService> consulInactiveServices) {
        // Placeholder for scaling out logic
        var inactiveServicesCount = consulInactiveServices.size();
        if (inactiveServicesCount >= numberOfServersToScaleOut) {
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
