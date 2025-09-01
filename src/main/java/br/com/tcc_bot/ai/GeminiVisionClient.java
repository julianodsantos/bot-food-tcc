package br.com.tcc_bot.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class GeminiVisionClient {

    @Value("${GCP_PROJECT}")
    private String projectId;
    @Value("${GCP_LOCATION:us-central1}")
    private String location;
    @Value("${VERTEX_MODEL:gemini-2.5-flash}")
    private String modelId;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    public PlateAnalysis analyzePlate(byte[] imageBytes, String mimeType) throws Exception {
        String token = fetchAccessToken();

        String instruction = """
                Você é um nutricionista. Analise a FOTO e devolva JSON com itens de comida no prato.
                Para cada item:
                - name (pt-BR), portion_label: small|medium|large, quantity_grams (estimada), confidence 0..1
                Não invente itens. Se incerto, use confidence baixa.
                """;

        Map<String, Object> req = buildRequest(imageBytes, mimeType, instruction);

        String url = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent",
                location, projectId, location, modelId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HttpStatus.OK.value()) {
            throw new RuntimeException("Gemini erro HTTP " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode textNode = mapper.readTree(resp.body())
                .path("candidates").path(0).path("content").path("parts").path(0).path("text");
        if (textNode.isMissingNode() || textNode.asText().isBlank()) {
            throw new RuntimeException("Resposta do modelo sem conteúdo de texto.");
        }
        return mapper.readValue(textNode.asText(), PlateAnalysis.class);
    }

    private Map<String, Object> buildRequest(byte[] image, String mime, String instruction) {
        String b64 = Base64.getEncoder().encodeToString(image);
        Map<String, Object> content = Map.of(
                "role", "user",
                "parts", List.of(
                        Map.of("text", instruction),
                        Map.of("inlineData", Map.of("mimeType", mime, "data", b64))
                )
        );

        Map<String, Object> schemaItem = Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "portion_label", Map.of("type", "string", "enum", List.of("small", "medium", "large")),
                        "quantity_grams", Map.of("type", "number"),
                        "confidence", Map.of("type", "number")
                ),
                "required", List.of("name", "portion_label")
        );
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("items", Map.of("type", "array", "items", schemaItem)),
                "required", List.of("items")
        );

        return Map.of(
                "contents", List.of(content),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", schema,
                        "maxOutputTokens", 2048
                )
        );
    }

    private String fetchAccessToken() throws Exception {
        GoogleCredentials cred = GoogleCredentials.getApplicationDefault()
                .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
        cred.refreshIfExpired();
        return cred.getAccessToken().getTokenValue();
    }

    // Resultado
    public static class PlateAnalysis {
        @JsonProperty("items")
        public List<FoodItem> items = List.of();
    }

    public static class FoodItem {
        @JsonProperty("name")
        public String name;
        @JsonProperty("portion_label")
        public String portionLabel;
        @JsonProperty("quantity_grams")
        public Double quantityGrams;
        @JsonProperty("confidence")
        public Double confidence;
    }
}
