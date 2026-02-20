package domain.utils;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import infrastructure.resources.rest.client.ConsulClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.wildfly.common.ref.Log_.logger;

@ApplicationScoped
public class AutoScaler {

    @ConfigProperty(name = "socket-proxy.uri")
    String dockerSocketProxyUri;

    @ConfigProperty(name = "container-runtime.network")
    String containerRuntimeNetwork;

    ConsulClient consulClient;


    public AutoScaler(ConsulClient consulClient) {
        this.consulClient = consulClient;
    }

    // 1. SCALE OUT: "Dumb" expansion using Docker Compose
    // We use CLI because creating a container with correct networks/volumes 
    // via raw API is incredibly complex.
    public void scaleOutWithDockerCompose(int targetCount) {
        try {
            System.out.println("🚀 Scaling OUT to " + targetCount + " instances...");
            
            ProcessBuilder pb = new ProcessBuilder(
                "podman", "compose", "-f","/Users/jonathas.santos/Documents/projects/tracker_reverse_proxy/files/docker/compose-haproxy.yml", "up", "--scale", "ws-app=" + targetCount, "-d", "ws-app"
            );
            
            pb.inheritIO(); // Show logs in console
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                System.out.println("✅ Scale Out Complete");
            } else {
                System.err.println("❌ Scale Out Failed");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void scaleOut(int targetCount) {
        try {
            System.out.println("🚀 Scaling OUT to " + targetCount + " instances...");

            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = dockerSocketProxyUri;
            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

            // 1. Find an existing ws-app container to use as template
            String filters = java.net.URLEncoder.encode("{\"name\":[\"ws-app\"]}", java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest listRequest = HttpRequest.newBuilder()
                                                 .uri(URI.create(baseUrl + "/containers/json?filters=" + filters))
                                                 .GET()
                                                 .build();

            HttpResponse<String> listResponse = client.send(listRequest, HttpResponse.BodyHandlers.ofString());
            var containers = objectMapper.readValue(listResponse.body(), com.fasterxml.jackson.databind.JsonNode[].class);

            if (containers.length == 0) {
                System.err.println("❌ No existing ws-app container found as template");
                return;
            }

            String templateContainerId = containers[0].get("Id").asText();
            String imageName = containers[0].get("Image").asText();

            // 2. Inspect template container for config
            HttpRequest inspectRequest = HttpRequest.newBuilder()
                                                    .uri(URI.create(baseUrl + "/containers/" + templateContainerId + "/json"))
                                                    .GET()
                                                    .build();

            HttpResponse<String> inspectResponse = client.send(inspectRequest, HttpResponse.BodyHandlers.ofString());
            var templateConfig = objectMapper.readTree(inspectResponse.body());

            int currentCount = containers.length;
            int toCreate = targetCount - currentCount;

            for (int i = 0; i < toCreate; i++) {
                // 3. Create container config
                var createConfig = objectMapper.createObjectNode();
                createConfig.put("Image", imageName);

                // Copy environment variables
                if (templateConfig.has("Config") && templateConfig.get("Config").has("Env")) {
                    createConfig.set("Env", templateConfig.get("Config").get("Env"));
                }

                // Copy exposed ports
                if (templateConfig.has("Config") && templateConfig.get("Config").has("ExposedPorts")) {
                    createConfig.set("ExposedPorts", templateConfig.get("Config").get("ExposedPorts"));
                }

                // Host config (volumes, etc.)
                var hostConfig = objectMapper.createObjectNode();
                if (templateConfig.has("HostConfig") && templateConfig.get("HostConfig").has("Binds")) {
                    hostConfig.set("Binds", templateConfig.get("HostConfig").get("Binds"));
                }
                createConfig.set("HostConfig", hostConfig);

                // 4. Create the container
                HttpRequest createRequest = HttpRequest.newBuilder()
                                                       .uri(URI.create(baseUrl + "/containers/create?name=ws-app-" + System.currentTimeMillis()))
                                                       .header("Content-Type", "application/json")
                                                       .POST(HttpRequest.BodyPublishers.ofString(createConfig.toString()))
                                                       .build();

                HttpResponse<String> createResponse = client.send(createRequest, HttpResponse.BodyHandlers.ofString());

                if (createResponse.statusCode() == 201) {
                    var newContainer = objectMapper.readTree(createResponse.body());
                    String newContainerId = newContainer.get("Id").asText();

                    // 5. Connect to network (if needed)
                    var networks = templateConfig.get("NetworkSettings").get("Networks");
                    networks.fieldNames().forEachRemaining(networkName -> {
                        System.out.println("🔗 Connecting new container to network: " + networkName);
                        if(networkName.equals(containerRuntimeNetwork)) {
                            try {
                                var connectConfig = objectMapper.createObjectNode();
                                connectConfig.put("Container", newContainerId);
    
                                HttpRequest connectRequest = HttpRequest.newBuilder()
                                                                        .uri(URI.create(baseUrl + "/networks/" + networkName + "/connect"))
                                                                        .header("Content-Type", "application/json")
                                                                        .POST(HttpRequest.BodyPublishers.ofString(connectConfig.toString()))
                                                                        .build();
                                client.send(connectRequest, HttpResponse.BodyHandlers.ofString());
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });

                    // 6. Start the container
                    HttpRequest startRequest = HttpRequest.newBuilder()
                                                          .uri(URI.create(baseUrl + "/containers/" + newContainerId + "/start"))
                                                          .POST(HttpRequest.BodyPublishers.noBody())
                                                          .build();

                    HttpResponse<String> startResponse = client.send(startRequest, HttpResponse.BodyHandlers.ofString());

                    if (startResponse.statusCode() == 204 || startResponse.statusCode() == 200) {
                        System.out.println("✅ Container " + newContainerId.substring(0, 12) + " created and started");
                    }
                } else {
                    System.err.println("❌ Failed to create container: " + createResponse.body());
                }
            }

            System.out.println("✅ Scale Out Complete");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 2. SCALE IN: "Smart" reduction using Docker API
    // We bypass Compose to kill the SPECIFIC container that is idle.
    public void stopSpecificContainer(String containerIp) {

        // 1. Find container ID by IP
        String socketProxyURI = dockerSocketProxyUri;
        String socketProxyUrl = socketProxyURI + "/containers/json";
        String containerId = null;
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(socketProxyUrl))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String responseBody = response.body();
                // Parse JSON to find container with matching IP
                var containers = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(responseBody, com.fasterxml.jackson.databind.JsonNode[].class);
                
                for (var container : containers) {
                    var networks = container.get("NetworkSettings").get("Networks");
                    for (var network : networks) {
                        var ipAddress = network.get("IPAddress").asText();
                        if (ipAddress.equals(containerIp)) {
                            containerId = container.get("Id").asText();
                            break;
                        }
                    }
                    if (containerId != null) break;
                }

                if (containerId == null) {
                    System.err.println("❌ No container found with IP: " + containerIp);
                    return;
                }

            } else {
                System.err.println("❌ Failed to list containers: " + response.body());
                return;
            }

        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        


        try {
            System.out.println("🔻 Scaling IN: Stopping idle container " + containerId);

            // Use the Socket Proxy we set up earlier!
            // Endpoint: POST /containers/{id}/stop
            String socketProxyStopUrl = dockerSocketProxyUri + "/containers/" + containerId + "/stop";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(socketProxyStopUrl))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204 || response.statusCode() == 200) {
                System.out.println("✅ Container " + containerId + " stopped successfully.");
                
                // Prune stopped containers after successful stop
                pruneStoppedContainers();
            } else {
                System.err.println("❌ Failed to stop container: " + response.body());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void pruneStoppedContainers() {
        try {
            System.out.println("🧹 Pruning stopped containers...");

            String socketProxyPruneUrl = dockerSocketProxyUri + "/containers/prune";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(socketProxyPruneUrl))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var pruneResponse = objectMapper.readTree(response.body());
                
                var containersDeleted = pruneResponse.get("ContainersDeleted");
                var spaceReclaimed = pruneResponse.get("SpaceReclaimed").asLong();
                
                int deletedCount = containersDeleted != null ? containersDeleted.size() : 0;
                System.out.println("✅ Pruned " + deletedCount + " stopped containers. Space reclaimed: " + spaceReclaimed + " bytes");
            } else {
                System.err.println("❌ Failed to prune containers: " + response.body());
            }

        } catch (Exception e) {
            System.err.println("❌ Error while pruning containers: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void scaleIn(int numberOfServers, Map<String, Integer> serverUtilization) {
        // Placeholder: In a real scenario, we would identify idle containers.
        // Here, we simulate stopping 'numberOfServers' idle containers.
        var consulServices = consulClient.getServiceInstances("ws-app");
        var activeOnlyConsulServices = consulServices.stream()
                .filter(s -> s.Checks.stream().allMatch(c -> c.Status.equals("passing")))
                .toList();
        var activeServiceWithLeastUtilization = activeOnlyConsulServices.stream()
                .sorted((s1, s2) -> {
                    var utilization1 = serverUtilization.getOrDefault(s1.Service.Address, Integer.MAX_VALUE);
                    var utilization2 = serverUtilization.getOrDefault(s2.Service.Address, Integer.MAX_VALUE);
                    return utilization1.compareTo(utilization2);
                })
                .toList();

        consulClient.toggleService(activeServiceWithLeastUtilization.getFirst().Service.ID, "true", "Scaling in due to low utilization");
    }
}