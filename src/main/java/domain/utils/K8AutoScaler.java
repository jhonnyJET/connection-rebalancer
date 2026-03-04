package domain.utils;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class K8AutoScaler {

    @Inject
    KubernetesClient kubernetesClient;

    /**
     * Retrieves all pods with matching labels
     * @param namespace The namespace to search in (null for all namespaces)
     * @param labelKey The label key to match
     * @param labelValue The label value to match
     * @return List of pods matching the label criteria
     */
    public List<Pod> getPodsWithLabel(String namespace, String labelKey, String labelValue) {
        try {
            List<Pod> allPods;
            
            if (namespace != null) {
                allPods = kubernetesClient.pods().inNamespace(namespace)
                        .withLabel(labelKey, labelValue)
                        .list().getItems();
            } else {
                allPods = kubernetesClient.pods().inAnyNamespace()
                        .withLabel(labelKey, labelValue)
                        .list().getItems();
            }

            return allPods;
        } catch (KubernetesClientException e) {
            Log.errorf("Error retrieving pods with label %s=%s: %s", labelKey, labelValue, e.getMessage());
            throw new RuntimeException("Failed to retrieve pods with label", e);
        }
    }

    /**
     * Retrieves all tracker pods using label matching
     * @param namespace The namespace to search in (null for all namespaces)
     * @return List of tracker pods
     */
    public List<Pod> getTrackerPods(String namespace) {
        return getPodsWithLabel(namespace, "app", "tracker");
    }

    /**
     * Retrieves all tracker pods in the default namespace
     * @return List of tracker pods in the default namespace
     */
    public List<Pod> getTrackerPods() {
        return getTrackerPods("default");
    }

    /**
     * Scales a deployment to the specified number of replicas
     * @param deploymentName The name of the deployment to scale
     * @param namespace The namespace where the deployment is located
     * @param replicas The desired number of replicas
     * @return true if successful, false otherwise
     */
    public boolean scaleDeployment(String deploymentName, String namespace, int replicas) {
        try {
            Log.infof("Scaling deployment %s in namespace %s to %d replicas", 
                     deploymentName, namespace, replicas);
            
            kubernetesClient.apps()
                    .deployments()
                    .inNamespace(namespace)
                    .withName(deploymentName)
                    .scale(replicas);
            
            Log.infof("Successfully scaled deployment %s to %d replicas", deploymentName, replicas);
            return true;
        } catch (KubernetesClientException e) {
            Log.errorf("Error scaling deployment %s: %s", deploymentName, e.getMessage());
            return false;
        }
    }

    /**
     * Patches a deployment to set the desired number of replicas
     * @param deploymentName The name of the deployment to patch
     * @param namespace The namespace where the deployment is located
     * @param replicas The desired number of replicas
     * @return true if successful, false otherwise
     */
    public boolean patchDeploymentReplicas(String deploymentName, String namespace, int replicas) {
        try {
            Log.infof("Patching deployment %s in namespace %s to %d replicas", 
                     deploymentName, namespace, replicas);
            
            Deployment deployment = kubernetesClient.apps()
                    .deployments()
                    .inNamespace(namespace)
                    .withName(deploymentName)
                    .get();
            
            if (deployment == null) {
                Log.errorf("Deployment %s not found in namespace %s", deploymentName, namespace);
                return false;
            }
            
            deployment.getSpec().setReplicas(replicas);
            
            kubernetesClient.apps()
                    .deployments()
                    .inNamespace(namespace)
                    .withName(deploymentName)
                    .patch(deployment);
            
            Log.infof("Successfully patched deployment %s to %d replicas", deploymentName, replicas);
            return true;
        } catch (KubernetesClientException e) {
            throw e;
            // Log.errorf("Error patching deployment %s: %s", deploymentName, e.getMessage());
            // return false;
        }
    }

    /**
     * Gets the current number of replicas for a deployment
     * @param deploymentName The name of the deployment
     * @param namespace The namespace where the deployment is located
     * @return The current number of replicas, or -1 if not found
     */
    public int getCurrentReplicas(String deploymentName, String namespace) {
        try {
            Deployment deployment = kubernetesClient.apps()
                    .deployments()
                    .inNamespace(namespace)
                    .withName(deploymentName)
                    .get();
            
            if (deployment == null) {
                Log.errorf("Deployment %s not found in namespace %s", deploymentName, namespace);
                return -1;
            }
            
            return deployment.getStatus().getReplicas();
        } catch (KubernetesClientException e) {
            Log.errorf("Error getting replicas for deployment %s: %s", deploymentName, e.getMessage());
            return -1;
        }
    }

    /**
     * Patches a pod to add or update a label
     * @param podName The name of the pod to patch
     * @param namespace The namespace where the pod is located
     * @param labelKey The label key to add or update
     * @param labelValue The label value to set
     * @return true if successful, false otherwise
     */
    public boolean patchPodLabel(String podName, String namespace, String labelKey, String labelValue) {
        try {
            Log.infof("Patching pod %s in namespace %s with label %s=%s", 
                     podName, namespace, labelKey, labelValue);
            
            Pod pod = kubernetesClient.pods()
                    .inNamespace(namespace)
                    .withName(podName)
                    .get();
            
            if (pod == null) {
                Log.errorf("Pod %s not found in namespace %s", podName, namespace);
                return false;
            }
            
            // Add or update the label
            pod.getMetadata().getLabels().put(labelKey, labelValue);
            
            kubernetesClient.pods()
                    .inNamespace(namespace)
                    .withName(podName)
                    .patch(pod);
            
            Log.infof("Successfully patched pod %s with label %s=%s", podName, labelKey, labelValue);
            return true;
        } catch (KubernetesClientException e) {
            Log.errorf("Error patching pod %s: %s", podName, e.getMessage());
            return false;
        }
    }
}
