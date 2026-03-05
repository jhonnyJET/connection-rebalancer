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
import domain.utils.K8AutoScaler;
import infrastructure.resources.rest.dto.ConsulService;
import io.fabric8.kubernetes.api.model.Pod;

@ApplicationScoped
public class WsSessionApi {

    private static Logger logger = Logger.getLogger(WsSessionApi.class.getName());

    private static final String K8_ENV_TYPE = "k8s";

    private static final String CONTAINER_RUNTIME_ENV_TYPE = "container_runtime";
    
    WsSessionService wsSessionService;

    ObjectMapper objectMapper;

    @ConfigProperty(name = "app.connection-rebalancer.environment.type")
    String CONNECTION_REBALANCER_ENVIRONMENT_TYPE;

    @ConfigProperty(name = "app.connection-rebalancer.kubernetes.app-label")
    String KUBERNETES_APP_LABEL;

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

    @Inject
    K8AutoScaler k8AutoScaler;

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
        var sanitizedEnvType = sanitizeEnvVariable(CONNECTION_REBALANCER_ENVIRONMENT_TYPE);
        logger.info("Starting session server utilization analysis for environment type: " + sanitizedEnvType + " lenghts compare: " + sanitizedEnvType.length() + " vs " + K8_ENV_TYPE.length() + " and " + CONTAINER_RUNTIME_ENV_TYPE.length());
        logger.info("is equal: " + sanitizedEnvType.equalsIgnoreCase("k8s"));
        if(CONTAINER_RUNTIME_ENV_TYPE.equalsIgnoreCase(sanitizedEnvType)){
            analyzeSessionServerUtilizationForContainerRuntimeEnvs();
        } 

        if(K8_ENV_TYPE.equalsIgnoreCase(sanitizedEnvType)){
            analyzeSessionServerUtilizationForKubernetesEnvs();
        }
    }

    
    public void analyzeSessionServerUtilizationForKubernetesEnvs() {
        var sanitizedK8AppLabel = sanitizeEnvVariable(KUBERNETES_APP_LABEL);
        var wsSessions = wsSessionService.findAllSessions();
        logger.info("Initiating analysis");        
        var activePods = k8AutoScaler.getPodsWithLabel("default", "traffic", "active");
        var inactivePods = k8AutoScaler.getPodsWithLabel("default", "traffic", "inactive");
        logger.info("List of active pods with name starting with " + sanitizedK8AppLabel + ": " + objectMapper.valueToTree(activePods).toString());
        logger.info("List of inactive pods with name starting with " + sanitizedK8AppLabel + ": " + objectMapper.valueToTree(inactivePods).toString());
        var cachedSessionUtilizationMap = wsSessionService.retrieveServerSessionUtilization(wsSessions);

        logger.info("Cached Session Map: " + objectMapper.valueToTree(cachedSessionUtilizationMap));

        if (wsSessions.isEmpty() && activePods.size() <= 1) {
            logger.log(Level.INFO, "No Sessions to analyze");
            return;
        }        

        var overrallActiveSessions = cachedSessionUtilizationMap.values().stream()
                .mapToInt(WsSessionUtilization::activeSessions)
                .sum();

        var overrallMaxSessions = activePods.size() * MAX_SESSIONS_PER_SERVER;
        
        if (overrallMaxSessions == 0) {
            logger.log(Level.INFO, "No Max Sessions configured, No pods to analyze balance");
            return;
        }
        Map<String, Integer> utilizationMapPercentMap = cachedSessionUtilizationMap.entrySet().stream()
               .map(p -> {
                return Map.of(p.getKey(), (int) (((float)p.getValue().activeSessions() / p.getValue().maxSessions()) * 100));
               })
               .flatMap(m -> m.entrySet().stream())
               .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));        

        var overallUtilizationPercent = (((float)overrallActiveSessions / overrallMaxSessions) * 100);
        var numberOfServersToScaleOut = 0;
        var numberOfServersToScaleIn = 0;

        if (overallUtilizationPercent > MAX_UTILIZATION_PERCENT) {            
            var maxTargetServerTreshold = Math.ceil(overrallActiveSessions / ((float)MAX_SESSIONS_PER_SERVER * ((float)MAX_UTILIZATION_PERCENT / 100.0)));
            numberOfServersToScaleOut = (int)maxTargetServerTreshold - activePods.size();
            var targetServerCount = activePods.size() + (int)numberOfServersToScaleOut;            
            scaleOutK8Servers(targetServerCount, numberOfServersToScaleOut, inactivePods);
        }

        if (overallUtilizationPercent < MIN_UTILIZATION_PERCENT) {
            if(cachedSessionUtilizationMap.size() <= 0 && activePods.size() <= 1){
                return;
            }

            if(cachedSessionUtilizationMap.size() <= 0 && activePods.size() > 1) {
                numberOfServersToScaleIn = activePods.size() - 1;
            } else {
                var averageTargetServerTreshold = Math.ceil(overrallActiveSessions / ((float)MAX_SESSIONS_PER_SERVER * ((float)MAX_UTILIZATION_PERCENT / 100.0)));                
                numberOfServersToScaleIn = (int)activePods.size() - (int)averageTargetServerTreshold;                
            }

            if(numberOfServersToScaleIn > 0) {
                scaleInK8SessionServers(numberOfServersToScaleIn, utilizationMapPercentMap, activePods);
            }
        }

        if(numberOfServersToScaleIn == 0 && numberOfServersToScaleOut == 0) {
            killK8ServersWithNoSessions(utilizationMapPercentMap, activePods, inactivePods);
        }
    }

    public void analyzeSessionServerUtilizationForContainerRuntimeEnvs() {
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

    public void killK8ServersWithNoSessions(Map<String, Integer> utilizationMapPercentMap, List<Pod> activePods, List<Pod> inactivePods) {
        Integer podsMarkedForDeletion = 0;
        for (Pod pod : inactivePods) {
           var isThereAnySessionConnectedToInactiveService = utilizationMapPercentMap.containsKey(pod.getStatus().getPodIP()) && utilizationMapPercentMap.get(pod.getStatus().getPodIP()) > 0;
            if (!isThereAnySessionConnectedToInactiveService) {
                k8AutoScaler.patchPodAnnotation(pod.getMetadata().getName(), pod.getMetadata().getNamespace(), "controller.kubernetes.io/pod-deletion-cost", "-100");
                podsMarkedForDeletion++;
            }
        }
        if(podsMarkedForDeletion > 0){
            var targetServerCount = activePods.size() + inactivePods.size() - podsMarkedForDeletion;
            k8AutoScaler.patchDeploymentReplicas(sanitizeEnvVariable(KUBERNETES_APP_LABEL), "default", targetServerCount);
        }
        logger.info("Pods marked for deletion: " + podsMarkedForDeletion);
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
        var sanitizedEnvType = sanitizeEnvVariable(CONNECTION_REBALANCER_ENVIRONMENT_TYPE);
        logger.info("Starting session server balance analysis for environment type: " + sanitizedEnvType);
        if(sanitizedEnvType.equalsIgnoreCase("container_runtime")){
            analyzeSessionServerBalanceForContainerRuntime();
        }

        if(sanitizedEnvType.equalsIgnoreCase("k8s")){
            analyzeSessionServerBalanceForKubernetesEnvs();
        }

    }

    public void analyzeSessionServerBalanceForKubernetesEnvs() {
        Logger.getLogger(WsSessionApi.class.getName()).info("Rebalancing started");
        var sanitizedK8AppLabel = sanitizeEnvVariable(KUBERNETES_APP_LABEL);
        var wsSessions = wsSessionService.findAllSessions();
        var wsSessionUtilizationMap = wsSessionService.retrieveServerSessionUtilization(wsSessions);
        var activePods = k8AutoScaler.getPodsWithLabel("default", "traffic", "active");
        // var inactivePods = k8AutoScaler.getPodsWithLabel("default", "traffic", "inactive");
        if (wsSessions.isEmpty()) {
            logger.info("No Sessions to rebalance");
            return;
        }
       var overrallActiveSessions = wsSessionUtilizationMap.values().stream()
                                                         .mapToInt(WsSessionUtilization::activeSessions)
                                                         .sum();

       var overrallMaxSessions = activePods.size() * MAX_SESSIONS_PER_SERVER;

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

        var activePodsWithNoSessions = activePods.stream()
            .filter(pod -> !utilizationMapPercentMap.containsKey(pod.getStatus().getPodIP()) || utilizationMapPercentMap.get(pod.getStatus().getPodIP()) == 0)
            .toList();

        activePodsWithNoSessions.forEach(pod -> utilizationMapPercentMap.put(pod.getStatus().getPodIP(), 0));        

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

    public void analyzeSessionServerBalanceForContainerRuntime() {
        var wsSessions = wsSessionService.findAllSessions();
        var wsSessionUtilizationMap = wsSessionService.retrieveServerSessionUtilization(wsSessions);
        var consulActiveServices = wsSessionService.getConsulActiveServices("ws-app");
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


    public void scaleInK8SessionServers(int numberOfServers, Map<String, Integer> serverUtilization, List<Pod> activePods) {
        var sanitizedK8AppLabel = sanitizeEnvVariable(KUBERNETES_APP_LABEL);
        System.out.println("Scaling in WebSocket session servers...");
        System.out.println("Number of servers to scale in: " + numberOfServers + " active pods: " + activePods.size() + " server utilization map: " + serverUtilization.toString());

        // Placeholder for scaling in logic
        var sortedActivePods = activePods.stream()
            .sorted(Comparator.comparingInt(p -> serverUtilization.getOrDefault(p.getStatus().getPodIP(), 0)))
            .toList();

        System.out.println("Sorted active pods by utilization: " + sortedActivePods.stream().map(p -> p.getStatus().getPodIP() + ":" + serverUtilization.getOrDefault(p.getStatus().getPodIP(), 0)).toList().toString());

        var podsToScaleIn = sortedActivePods.stream()
            .limit(numberOfServers)
            .toList();

        System.out.println("Pods selected to scale in: " + podsToScaleIn.stream().map(p -> p.getMetadata().getName()).toList().toString());

            podsToScaleIn.forEach(pod -> {
                logger.info("Cordoning pod " + pod.getMetadata().getName() + " with IP " + pod.getStatus().getPodIP());
                // Need to implement a path to update the pod label to a draining status so that it gets cordoned/removed from load balancing rotation                        
                k8AutoScaler.patchPodLabel(pod.getMetadata().getName(), pod.getMetadata().getNamespace(), "traffic", "inactive");
            });
    }

    public void scaleInSessionServers(int numberOfServers, Map<String, Integer> serverUtilization) {
        System.out.println("Scaling in WebSocket session servers...");
        autoScaler.scaleIn(numberOfServers, serverUtilization);
        // Send command to docker or k8s orchestrator to cordon/remove server from load balancer
        // NOTE: Cordon server with the least number of active sessions
    }



    public void scaleOutK8Servers(int targetServerCount, int numberOfServersToScaleOut, List<Pod> inactivePods) {
        var sanitizedK8AppLabel = sanitizeEnvVariable(KUBERNETES_APP_LABEL);
            if (inactivePods.size() >= numberOfServersToScaleOut) {
                inactivePods.stream()
                    .limit(numberOfServersToScaleOut)
                    .forEach(pod -> {
                        logger.info("Activating inactive pod " + pod.getMetadata().getName());
                        // Need to implement a path to update the pod label back to KUBERNETES_APP_LABEL so that it gets considered back into load balancing rotation                        
                        k8AutoScaler.patchPodLabel(pod.getMetadata().getName(), pod.getMetadata().getNamespace(), "traffic", "active");
                    });
            }

            var serversToScaleOut = targetServerCount - inactivePods.size();
            if(serversToScaleOut - inactivePods.size() <= 0){
                logger.info("No need to scale out, inactive pods can handle the target server count.");
                return;
            }
            k8AutoScaler.patchDeploymentReplicas(sanitizedK8AppLabel, "default", serversToScaleOut);
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

    private String sanitizeEnvVariable(String envVariable) {
        return envVariable.trim().replaceAll("^\"|\"$", "");
    }
}
