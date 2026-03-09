package api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import domain.SsePersistentSession;
import domain.SseSessionService;
import domain.SseSessionUtilization;
import domain.utils.AutoScaler;
import domain.utils.K8AutoScaler;
import infrastructure.resources.rest.dto.ConsulService;
import io.fabric8.kubernetes.api.model.Pod;
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

@ApplicationScoped
public class SseSessionApi {

    private static final Logger logger = Logger.getLogger(SseSessionApi.class.getName());

    SseSessionService sseSessionService;
    ObjectMapper objectMapper;

    @ConfigProperty(name = "app.connection-rebalancer.kubernetes.app-label")
    String kubernetesAppLabel;

    @ConfigProperty(name = "connection.limit.per.host")
    Integer maxSessionsPerServer;

    @ConfigProperty(name = "overutilized.tolerance.percent")
    Integer overutilizedTolerancePercent;

    @ConfigProperty(name = "underutilized.tolerance.percent")
    Integer underutilizedTolerancePercent;

    @ConfigProperty(name = "max.utilization.percent")
    Integer maxUtilizationPercent;

    @ConfigProperty(name = "min.utilization.percent")
    Integer minUtilizationPercent;

    @ConfigProperty(name = "app.connection-rebalancer.environment.type")
    String environmentType;

    @Inject
    K8AutoScaler k8AutoScaler;

    @Inject
    AutoScaler autoScaler;

    public SseSessionApi(ObjectMapper objectMapper, SseSessionService sseSessionService) {
        this.objectMapper = objectMapper;
        this.sseSessionService = sseSessionService;
    }

    public List<SsePersistentSession> findAllSessions() throws JsonProcessingException {
        var sseSessions = sseSessionService.findAllSessions();
        objectMapper.writeValueAsString(sseSessions);
        return sseSessions;
    }

    public void sendAdminCommand(Map<String, Integer> sessions) {
        sseSessionService.sendAdminCommand(sessions);
    }

    public void analyzeSessionServerUtilization() {
        var sanitizedEnvType = sanitizeEnvVariable(environmentType);
        if (sanitizedEnvType.equalsIgnoreCase("container_runtime")) {
            analyzeSessionServerUtilizationForContainerRuntimeEnvs();
        }
        if (sanitizedEnvType.equalsIgnoreCase("k8s")) {
            analyzeSessionServerUtilizationForKubernetesEnvs();
        }
    }

    public void analyzeSessionServerUtilizationForKubernetesEnvs() {
        var sanitizedK8AppLabel = sanitizeEnvVariable(kubernetesAppLabel);
        var sseSessions = sseSessionService.findAllSessions();
        logger.info("Initiating SSE utilization analysis");
        var activePods = k8AutoScaler.getPodsWithLabel("default", "traffic", "active");
        var inactivePods = k8AutoScaler.getPodsWithLabel("default", "traffic", "inactive");
        logger.info("List of active pods with name starting with " + sanitizedK8AppLabel + ": "
                + objectMapper.valueToTree(activePods));
        logger.info("List of inactive pods with name starting with " + sanitizedK8AppLabel + ": "
                + objectMapper.valueToTree(inactivePods));

        var cachedSessionUtilizationMap = sseSessionService.retrieveServerSessionUtilization(sseSessions);
        Map<String, Integer> utilizationMapPercentMap = cachedSessionUtilizationMap.entrySet().stream()
                .map(p -> Map.of(p.getKey(), (int) (((float) p.getValue().activeSessions() / p.getValue().maxSessions()) * 100)))
                .flatMap(m -> m.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        logger.info("Cached SSE Session Map: " + objectMapper.valueToTree(cachedSessionUtilizationMap));

        if (sseSessions.isEmpty() && activePods.size() <= 1) {
            logger.log(Level.INFO, "No SSE sessions to analyze");
            killK8ServersWithNoSessions(utilizationMapPercentMap, activePods, inactivePods);
            return;
        }

        var overallActiveSessions = cachedSessionUtilizationMap.values().stream()
                .mapToInt(SseSessionUtilization::activeSessions)
                .sum();

        var overallMaxSessions = activePods.size() * maxSessionsPerServer;

        if (overallMaxSessions == 0) {
            logger.log(Level.INFO, "No max sessions configured, no pods to analyze balance");
            return;
        }

        var overallUtilizationPercent = (((float) overallActiveSessions / overallMaxSessions) * 100);
        var numberOfServersToScaleOut = 0;
        var numberOfServersToScaleIn = 0;

        if (overallUtilizationPercent > maxUtilizationPercent) {
            var maxTargetServerThreshold = Math.ceil(
                    overallActiveSessions / ((float) maxSessionsPerServer * ((float) maxUtilizationPercent / 100.0)));
            numberOfServersToScaleOut = (int) maxTargetServerThreshold - activePods.size();
            scaleOutK8Servers(numberOfServersToScaleOut, activePods, inactivePods);
        }

        if (overallUtilizationPercent < minUtilizationPercent) {
            if (cachedSessionUtilizationMap.isEmpty() && activePods.size() <= 1) {
                return;
            }

            if (cachedSessionUtilizationMap.isEmpty() && activePods.size() > 1) {
                numberOfServersToScaleIn = activePods.size() - 1;
            } else {
                var averageTargetServerThreshold = Math.ceil(
                        overallActiveSessions / ((float) maxSessionsPerServer * ((float) maxUtilizationPercent / 100.0)));
                numberOfServersToScaleIn = activePods.size() - (int) averageTargetServerThreshold;
            }

            if (numberOfServersToScaleIn > 0) {
                scaleInK8SessionServers(numberOfServersToScaleIn, utilizationMapPercentMap, activePods);
            }
        }

        if (numberOfServersToScaleIn == 0 && numberOfServersToScaleOut == 0) {
            killK8ServersWithNoSessions(utilizationMapPercentMap, activePods, inactivePods);
        }
    }

    public void killK8ServersWithNoSessions(Map<String, Integer> utilizationMapPercentMap, List<Pod> activePods,
            List<Pod> inactivePods) {
        int podsMarkedForDeletion = 0;
        for (Pod pod : inactivePods) {
            var isThereAnySessionConnectedToInactiveService = utilizationMapPercentMap.containsKey(pod.getStatus().getPodIP())
                    && utilizationMapPercentMap.get(pod.getStatus().getPodIP()) > 0;
            if (!isThereAnySessionConnectedToInactiveService) {
                k8AutoScaler.patchPodAnnotation(pod.getMetadata().getName(), pod.getMetadata().getNamespace(),
                        "controller.kubernetes.io/pod-deletion-cost", "-100");
                podsMarkedForDeletion++;
            }
        }
        if (podsMarkedForDeletion > 0) {
            var targetServerCount = activePods.size() + inactivePods.size() - podsMarkedForDeletion;
            k8AutoScaler.patchDeploymentReplicas(sanitizeEnvVariable(kubernetesAppLabel), "default", targetServerCount);
        }
        logger.info("SSE pods marked for deletion: " + podsMarkedForDeletion);
    }

    public void analyzeSessionServerUtilizationForContainerRuntimeEnvs() {
        var sseSessions = sseSessionService.findAllSessions();
        var sessionUtilizationMap = sseSessionService.retrieveServerSessionUtilization(sseSessions);
        var consulActiveServices = sseSessionService.getConsulActiveServices("sse-app");
        var consulInactiveServices = sseSessionService.getConsulInactiveServices("sse-app");

        if (sseSessions.isEmpty() && consulActiveServices.size() <= 1) {
            logger.log(Level.INFO, "No SSE sessions to analyze");
            return;
        }

        var overallActiveSessions = sessionUtilizationMap.values().stream()
                .mapToInt(SseSessionUtilization::activeSessions)
                .sum();

        var overallMaxSessions = consulActiveServices.size() * maxSessionsPerServer;

        Map<String, Integer> utilizationMapPercentMap = sessionUtilizationMap.entrySet().stream()
                .map(p -> Map.of(p.getKey(), (int) (((float) p.getValue().activeSessions() / p.getValue().maxSessions()) * 100)))
                .flatMap(m -> m.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (overallMaxSessions == 0) {
            logger.log(Level.INFO, "No max sessions configured, cannot analyze SSE balance");
            return;
        }

        var overallUtilizationPercent = (((float) overallActiveSessions / overallMaxSessions) * 100);
        var numberOfServersToScaleOut = 0;
        var numberOfServersToScaleIn = 0;

        if (overallUtilizationPercent > maxUtilizationPercent) {
            var maxTargetServerThreshold = Math.ceil(
                    overallActiveSessions / ((float) maxSessionsPerServer * ((float) maxUtilizationPercent / 100.0)));
            numberOfServersToScaleOut = (int) maxTargetServerThreshold - consulActiveServices.size();
            var targetServerCount = consulActiveServices.size() + numberOfServersToScaleOut;
            scaleOutSessionServers(targetServerCount, numberOfServersToScaleOut, consulInactiveServices);
        }

        if (overallUtilizationPercent < minUtilizationPercent) {
            if (sessionUtilizationMap.isEmpty() && consulActiveServices.size() <= 1) {
                return;
            }

            if (sessionUtilizationMap.isEmpty() && consulActiveServices.size() > 1) {
                numberOfServersToScaleIn = consulActiveServices.size() - 1;
            } else {
                var averageTargetServerThreshold = Math.ceil(
                        overallActiveSessions / ((float) maxSessionsPerServer * ((float) maxUtilizationPercent / 100.0)));
                numberOfServersToScaleIn = consulActiveServices.size() - (int) averageTargetServerThreshold;
            }

            if (numberOfServersToScaleIn > 0) {
                scaleInSessionServers(numberOfServersToScaleIn, utilizationMapPercentMap);
            }
        }

        logger.info("SSE Summary: Overall Active Sessions: " + overallActiveSessions +
                    ", Overall Max Sessions: " + overallMaxSessions +
                    ", Overall Utilization Percent: " + overallUtilizationPercent +
                    "%, Servers to scale out: " + numberOfServersToScaleOut +
                    ", Servers to scale in: " + numberOfServersToScaleIn);
        logger.info("SSE Utilization Percent Map: " + utilizationMapPercentMap);

        if (numberOfServersToScaleIn == 0 && numberOfServersToScaleOut == 0) {
            killServersWithNoSessions(utilizationMapPercentMap, consulActiveServices);
        }
    }

    public void killServersWithNoSessions(Map<String, Integer> utilizationMapPercentMap, List<ConsulService> consulActiveServices) {
        var consulInactiveServices = sseSessionService.getConsulInactiveServices("sse-app");
        consulInactiveServices.forEach(service -> {
            var isThereAnySessionConnectedToInactiveService = utilizationMapPercentMap.containsKey(service.Service.Address)
                    && utilizationMapPercentMap.get(service.Service.Address) > 0;
            if (!isThereAnySessionConnectedToInactiveService) {
                autoScaler.stopSpecificContainer(service.Service.Address);
            }
        });
    }

    public void scaleOutSessionServers(int targetServerCount, int numberOfServersToScaleOut, List<ConsulService> consulInactiveServices) {
        var inactiveServicesCount = consulInactiveServices.size();
        if (inactiveServicesCount >= numberOfServersToScaleOut) {
            consulInactiveServices.stream()
                    .limit(numberOfServersToScaleOut)
                    .forEach(service -> {
                        logger.info("Activating inactive SSE service " + service.Service.ID + " at " + service.Service.Address);
                        sseSessionService.toggleConsulService(service.Service.ID, "false", "Activating service due to scale out request");
                    });
        }
        var serversToScaleOut = targetServerCount - inactiveServicesCount;
        if (serversToScaleOut <= 0) {
            logger.info("No need to scale out, inactive SSE services can handle the target server count.");
            return;
        }
        logger.info("Scaling out SSE session servers...");
        autoScaler.scaleOut(serversToScaleOut);
    }

    public void scaleInSessionServers(int numberOfServers, Map<String, Integer> serverUtilization) {
        logger.info("Scaling in SSE session servers...");
        autoScaler.scaleIn(numberOfServers, serverUtilization);
    }

    public void analyzeSessionServerBalance() {
        var sanitizedEnvType = sanitizeEnvVariable(environmentType);
        if (sanitizedEnvType.equalsIgnoreCase("container_runtime")) {
            analyzeSessionServerBalanceForContainerRuntime();
        }
        if (sanitizedEnvType.equalsIgnoreCase("k8s")) {
            analyzeSessionServerBalanceForKubernetesEnvs();
        }
    }

    public void analyzeSessionServerBalanceForContainerRuntime() {
        var sseSessions = sseSessionService.findAllSessions();
        var sessionUtilizationMap = sseSessionService.retrieveServerSessionUtilization(sseSessions);
        var consulActiveServices = sseSessionService.getConsulActiveServices("tracker");

        if (sseSessions.isEmpty()) {
            logger.info("No SSE sessions to rebalance");
            return;
        }

        var overallActiveSessions = sessionUtilizationMap.values().stream()
                .mapToInt(SseSessionUtilization::activeSessions)
                .sum();

        var overallMaxSessions = consulActiveServices.size() * maxSessionsPerServer;

        if (overallMaxSessions == 0) {
            logger.info("No max sessions configured, cannot analyze SSE balance");
            return;
        }

        var overallUtilizationPercent = (int) ((float) overallActiveSessions / overallMaxSessions * 100);

        Map<String, Integer> underUtilizedServers = new HashMap<>();
        Map<String, Integer> overUtilizedServers = new HashMap<>();
        Map<String, Integer> utilizationMapPercentMap = sessionUtilizationMap.entrySet().stream()
                .map(p -> Map.of(p.getKey(), (int) (((float) p.getValue().activeSessions() / p.getValue().maxSessions()) * 100)))
                .flatMap(m -> m.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        var activeConsulServicesWithNoSessions = consulActiveServices.stream()
                .filter(service -> !utilizationMapPercentMap.containsKey(service.Service.Address)
                        || utilizationMapPercentMap.get(service.Service.Address) == 0)
                .toList();

        activeConsulServicesWithNoSessions.forEach(service -> utilizationMapPercentMap.put(service.Service.Address, 0));

        utilizationMapPercentMap.keySet().forEach(server -> {
            if (utilizationMapPercentMap.get(server) > (overallUtilizationPercent + overutilizedTolerancePercent)) {
                overUtilizedServers.put(server, utilizationMapPercentMap.get(server));
            } else if (utilizationMapPercentMap.get(server) < overallUtilizationPercent) {
                underUtilizedServers.put(server, utilizationMapPercentMap.get(server));
            }
        });

        var sortedOverUtilizedServers = overUtilizedServers.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .toList();

        logger.info("SSE rebalancing summary: Overutilized Servers: " + overUtilizedServers
                + ", Underutilized Servers: " + underUtilizedServers
                + ", Overall Utilization Percent: " + overallUtilizationPercent + "%, "
                + "Overall Active Sessions: " + overallActiveSessions);
        logger.info("SSE utilization percent map: " + utilizationMapPercentMap);

        sortedOverUtilizedServers.forEach(s -> {
            double maxSessions = sessionUtilizationMap.get(s.getKey()).maxSessions();
            double activeSessions = sessionUtilizationMap.get(s.getKey()).activeSessions();
            double activeSessionAverageThreshold = Math.ceil((maxSessions * ((double) overallUtilizationPercent / 100)));
            int numberOfSessionsToOffload = (int) (activeSessions - activeSessionAverageThreshold);
            offLoadSessions(s.getKey(), numberOfSessionsToOffload);
        });
    }

    public void analyzeSessionServerBalanceForKubernetesEnvs() {
        logger.info("SSE rebalancing started");
        var sseSessions = sseSessionService.findAllSessions();
        var sessionUtilizationMap = sseSessionService.retrieveServerSessionUtilization(sseSessions);
        var activePods = k8AutoScaler.getPodsWithLabel("default", "traffic", "active");

        if (sseSessions.isEmpty()) {
            logger.info("No SSE sessions to rebalance");
            return;
        }

        var overallActiveSessions = sessionUtilizationMap.values().stream()
                .mapToInt(SseSessionUtilization::activeSessions)
                .sum();

        var overallMaxSessions = activePods.size() * maxSessionsPerServer;

        if (overallMaxSessions == 0) {
            logger.info("No max sessions configured, cannot analyze SSE balance");
            return;
        }

        var overallUtilizationPercent = (int) ((float) overallActiveSessions / overallMaxSessions * 100);

        Map<String, Integer> underUtilizedServers = new HashMap<>();
        Map<String, Integer> overUtilizedServers = new HashMap<>();
        Map<String, Integer> utilizationMapPercentMap = sessionUtilizationMap.entrySet().stream()
                .map(p -> Map.of(p.getKey(), (int) (((float) p.getValue().activeSessions() / p.getValue().maxSessions()) * 100)))
                .flatMap(m -> m.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        var activePodsWithNoSessions = activePods.stream()
                .filter(pod -> !utilizationMapPercentMap.containsKey(pod.getStatus().getPodIP())
                        || utilizationMapPercentMap.get(pod.getStatus().getPodIP()) == 0)
                .toList();

        activePodsWithNoSessions.forEach(pod -> utilizationMapPercentMap.put(pod.getStatus().getPodIP(), 0));

        utilizationMapPercentMap.keySet().forEach(server -> {
            if (utilizationMapPercentMap.get(server) > (overallUtilizationPercent + overutilizedTolerancePercent)) {
                overUtilizedServers.put(server, utilizationMapPercentMap.get(server));
            } else if (utilizationMapPercentMap.get(server) < overallUtilizationPercent) {
                underUtilizedServers.put(server, utilizationMapPercentMap.get(server));
            }
        });

        var sortedOverUtilizedServers = overUtilizedServers.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .toList();

        logger.info("SSE rebalancing summary: Overutilized Servers: " + overUtilizedServers
                + ", Underutilized Servers: " + underUtilizedServers
                + ", Overall Utilization Percent: " + overallUtilizationPercent + "%, "
                + "Overall Active Sessions: " + overallActiveSessions);
        logger.info("SSE utilization percent map: " + utilizationMapPercentMap);

        sortedOverUtilizedServers.forEach(s -> {
            double maxSessions = sessionUtilizationMap.get(s.getKey()).maxSessions();
            double activeSessions = sessionUtilizationMap.get(s.getKey()).activeSessions();
            double activeSessionAverageThreshold = Math.ceil((maxSessions * ((double) overallUtilizationPercent / 100)));
            int numberOfSessionsToOffload = (int) (activeSessions - activeSessionAverageThreshold);
            offLoadSessions(s.getKey(), numberOfSessionsToOffload);
        });
    }

    private void offLoadSessions(String fromServerId, int numberOfSessions) {
        logger.info("Offloading " + numberOfSessions + " SSE sessions from server " + fromServerId);
        sseSessionService.dropServerSessions(fromServerId, numberOfSessions);
    }

    public void scaleInK8SessionServers(int numberOfServers, Map<String, Integer> serverUtilization, List<Pod> activePods) {
        System.out.println("Scaling in SSE session servers...");
        System.out.println("Number of servers to scale in: " + numberOfServers + " active pods: " + activePods.size()
                + " server utilization map: " + serverUtilization);

        var sortedActivePods = activePods.stream()
                .sorted(Comparator.comparingInt(p -> serverUtilization.getOrDefault(p.getStatus().getPodIP(), 0)))
                .toList();

        System.out.println("Sorted active pods by utilization: " + sortedActivePods.stream()
                .map(p -> p.getStatus().getPodIP() + ":" + serverUtilization.getOrDefault(p.getStatus().getPodIP(), 0))
                .toList());

        var podsToScaleIn = sortedActivePods.stream()
                .limit(numberOfServers)
                .toList();

        System.out.println("Pods selected to scale in: "
                + podsToScaleIn.stream().map(p -> p.getMetadata().getName()).toList());

        podsToScaleIn.forEach(pod -> {
            logger.info("Cordoning pod " + pod.getMetadata().getName() + " with IP " + pod.getStatus().getPodIP());
            k8AutoScaler.patchPodLabel(pod.getMetadata().getName(), pod.getMetadata().getNamespace(), "traffic", "inactive");
        });
    }

    public void scaleOutK8Servers(int numberOfServersToScaleOut, List<Pod> activePods, List<Pod> inactivePods) {
        var sanitizedK8AppLabel = sanitizeEnvVariable(kubernetesAppLabel);
        var targetServerCount = activePods.size() + numberOfServersToScaleOut;

        inactivePods.stream()
                .limit(numberOfServersToScaleOut)
                .forEach(pod -> {
                    logger.info("Activating inactive pod " + pod.getMetadata().getName());
                    k8AutoScaler.patchPodLabel(pod.getMetadata().getName(), pod.getMetadata().getNamespace(), "traffic",
                            "active");
                });

        var numberOfServersToScaleOutWithActivatedPods = activePods.size() + inactivePods.size() >= targetServerCount
                ? 0
                : targetServerCount - (activePods.size() + inactivePods.size());
        if (numberOfServersToScaleOutWithActivatedPods <= 0) {
            logger.info("No need to scale out, inactive pods can handle the target server count.");
            return;
        }
        k8AutoScaler.patchDeploymentReplicas(sanitizedK8AppLabel, "default", targetServerCount);
    }

    private String sanitizeEnvVariable(String envVariable) {
        return envVariable.trim().replaceAll("^\"|\"$", "");
    }
}
