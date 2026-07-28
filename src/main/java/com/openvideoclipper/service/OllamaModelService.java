package com.openvideoclipper.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;
import java.util.logging.Level;

@ApplicationScoped
public class OllamaModelService {
    private static final Logger LOG = Logger.getLogger(OllamaModelService.class.getName());
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public List<String> fetchModels(String ollamaUrl) {
        try {
            // Ensure URL has protocol and no trailing slash
            String url = ollamaUrl.trim();
            if (!url.startsWith("http")) {
                url = "http://" + url;
            }
            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseModelNames(response.body());
            } else {
                LOG.warning("Ollama API returned status: " + response.statusCode());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to fetch Ollama models from " + ollamaUrl, e);
        }
        return new ArrayList<>();
    }

    private List<String> parseModelNames(String json) {
        List<String> models = new ArrayList<>();
        // Simple regex to find "name":"..." patterns in the JSON response
        // Response format: {"models":[{"name":"llama3:latest",...},...]}
        Pattern pattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            models.add(matcher.group(1));
        }
        return models;
    }
}
