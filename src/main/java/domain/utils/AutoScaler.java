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

@ApplicationScoped
public class AutoScaler {

    ConsulClient consulClient;


    public AutoScaler(ConsulClient consulClient) {
        this.consulClient = consulClient;
    }

    // 1. SCALE OUT: "Dumb" expansion using Docker Compose
    // We use CLI because creating a container with correct networks/volumes 
    // via raw API is incredibly complex.
    public void scaleOut(int targetCount) {
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

    // 2. SCALE IN: "Smart" reduction using Docker API
    // We bypass Compose to kill the SPECIFIC container that is idle.
    public void stopSpecificContainer(String containerIp) {

        // 1. Find container ID by IP
        String socketProxyUrl = "http://localhost:2375/containers/json";
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
            String socketProxyStopUrl = "http://localhost:2375/containers/" + containerId + "/stop";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(socketProxyStopUrl))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204 || response.statusCode() == 200) {
                System.out.println("✅ Container " + containerId + " stopped successfully.");
            } else {
                System.err.println("❌ Failed to stop container: " + response.body());
            }

        } catch (Exception e) {
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
        System.out.println("Consul Services: " + consulServices.toString());
        var activeServiceWithLeastUtilization = activeOnlyConsulServices.stream()
                .sorted((s1, s2) -> {
                    var utilization1 = serverUtilization.getOrDefault(s1.Service.Address, Integer.MAX_VALUE);
                    var utilization2 = serverUtilization.getOrDefault(s2.Service.Address, Integer.MAX_VALUE);
                    return Integer.compare(utilization1, utilization2);
                })
                .toList();
        consulClient.toggleService(activeServiceWithLeastUtilization.getFirst().Service.ID, "true", "Scaling in due to low utilization");
    }
}