package api;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import domain.GrpcSessionService;
import domain.SseSessionService;
import domain.WsSessionService;
import domain.utils.AutoScaler;
import domain.utils.K8AutoScaler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ScalingApi {

    @ConfigProperty(name = "container.runtime.app.name")
    String containerRuntimeAppName;

    @ConfigProperty(name = "app.connection-rebalancer.kubernetes.app-label")
    String kubernetesAppLabel;

    @ConfigProperty(name = "app.connection-rebalancer.environment.type")
    String environmentType;    

    @Inject
    private WsSessionApi wsSessionApi;

    @Inject
    private SseSessionApi sseSessionApi;

    @Inject
    private GrpcSessionApi grpcSessionApi;

    @Inject
    private WsSessionService wsSessionService;

    @Inject
    private SseSessionService sseSessionService;

    @Inject
    private GrpcSessionService grpcSessionService;

    @Inject
    AutoScaler autoScaler;

    @Inject
    K8AutoScaler k8AutoScaler;    

    public void analyzeSessionServerBalance() {
        wsSessionApi.analyzeSessionServerBalance();
        sseSessionApi.analyzeSessionServerBalance();
        grpcSessionApi.analyzeSessionServerBalance();
    }

    public void analyzeSessionServerUtilization() {
        var sanitizedEnvType = sanitizeEnvVariable(environmentType);
        if (sanitizedEnvType.equalsIgnoreCase("container_runtime")) {
            var consulActiveServices = sseSessionService.getConsulActiveServices(sanitizeEnvVariable(containerRuntimeAppName));
            var consulInactiveServices = sseSessionService.getConsulInactiveServices(sanitizeEnvVariable(containerRuntimeAppName));
            var activeAndInactiveServicesCount = consulActiveServices.size() + consulInactiveServices.size();
            wsSessionApi.analyzeSessionServerUtilizationForContainerRuntimeEnvs();
            sseSessionApi.analyzeSessionServerUtilizationForContainerRuntimeEnvs();
            grpcSessionApi.analyzeSessionServerUtilizationForContainerRuntimeEnvs();
            killContainerRuntimeServersWithNoSessions(activeAndInactiveServicesCount);
        }
        if (sanitizedEnvType.equalsIgnoreCase("k8s")) {
            var activePods = k8AutoScaler.getPodsWithLabel("default", "traffic", "active");
            var inactivePods = k8AutoScaler.getPodsWithLabel("default", "traffic", "inactive");
            var activeAndInactivePodsCount = activePods.size() + inactivePods.size();
            wsSessionApi.analyzeSessionServerUtilizationForKubernetesEnvs();
            sseSessionApi.analyzeSessionServerUtilizationForKubernetesEnvs();
            grpcSessionApi.analyzeSessionServerUtilizationForKubernetesEnvs();
            killK8ServersWithNoSessions(activeAndInactivePodsCount);
        }
    }

    public void killContainerRuntimeServersWithNoSessions(int activeAndInactiveServicesCount) {
        var wsSessions = wsSessionService.findAllSessions();
        var sseSessions = sseSessionService.findAllSessions();
        var grpcSessions = grpcSessionService.findAllSessions();

        if (wsSessions.isEmpty() && sseSessions.isEmpty() && grpcSessions.isEmpty() && activeAndInactiveServicesCount > 1) {
            autoScaler.scaleOut(1, sanitizeEnvVariable(containerRuntimeAppName));
        }
    }

    public void killK8ServersWithNoSessions(int activeAndInactivePodsCount) {
        var wsSessions = wsSessionService.findAllSessions();
        var sseSessions = sseSessionService.findAllSessions();
        var grpcSessions = grpcSessionService.findAllSessions();

        if (wsSessions.isEmpty() && sseSessions.isEmpty() && grpcSessions.isEmpty() && activeAndInactivePodsCount > 1) {
            k8AutoScaler.patchDeploymentReplicas(sanitizeEnvVariable(kubernetesAppLabel), "default", 1);
        }
    }

    private String sanitizeEnvVariable(String envVariable) {
        return envVariable.trim().replaceAll("^\"|\"$", "");
    }    
}

