package br.com.tcc_bot.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class GeminiVisionClient {
    private final ObjectMapper mapper;
    private final RestClient restClient;

    public GeminiVisionClient(RestClient.Builder builder, ObjectMapper mapper) {
        this.restClient = builder.build();
        this.mapper = mapper;
    }

    public PlateAnalysis analyzePlate(byte[] imageBytes) throws Exception {
        String mimeType = "image/jpeg";
        String token = fetchAccessToken();

        String instruction = """
                Atue como um Nutricionista Sênior especialista em Visão Computacional e USDA.
                
                Analise a imagem e gere um JSON estrito com os itens do prato.
                
                DIRETRIZES TÉCNICAS:
                1. Escala: Assuma prato padrão de 26cm.
                2. Vocabulário: Use termos técnicos exatos do USDA no campo 'name_en' (ex: "Rice, white, long-grain, cooked").
                3. Preparo: Diferencie Frito/Cozido/Assado e Com/Sem pele.
                
                No campo 'reasoning', seja TELEGRÁFICO e direto (máximo 5 palavras).
                Ex: "Textura fibrosa, brilho de óleo". Não escreva frases longas.
                """;

        Map<String, Object> req = buildRequest(imageBytes, mimeType, instruction);

        String url = "https://aiplatform.googleapis.com/v1/projects/tcc-bot-wpp/locations/us-central1/publishers/google/models/gemini-3-pro-preview:generateContent";

        String responseBody = restClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(String.class);

        JsonNode textNode = mapper.readTree(responseBody)
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

        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("temperature", 0.2);
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseSchema", getResponseSchema());
        generationConfig.put("maxOutputTokens", 4096);

        return Map.of(
                "contents", List.of(content),
                "generationConfig", generationConfig
        );
    }

    private Map<String, Object> getResponseSchema() {
        Map<String, Object> schemaItem = Map.of(
                "type", "object",
                "properties", Map.of(
                        "name_pt", Map.of("type", "string"),
                        "name_en", Map.of("type", "string"),
                        "reasoning", Map.of("type", "string"),
                        "portion_label", Map.of("type", "string", "enum", List.of("small", "medium", "large")),
                        "quantity_grams", Map.of("type", "number"),
                        "confidence", Map.of("type", "number")
                ),
                "required", List.of("name_pt", "name_en", "portion_label", "quantity_grams", "reasoning")
        );
        return Map.of(
                "type", "object",
                "properties", Map.of("items", Map.of("type", "array", "items", schemaItem)),
                "required", List.of("items")
        );
    }

    private String fetchAccessToken() throws Exception {
        GoogleCredentials cred = GoogleCredentials.getApplicationDefault()
                .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
        cred.refreshIfExpired();
        return cred.getAccessToken().getTokenValue();
    }

    public static class PlateAnalysis {
        @JsonProperty("items")
        public List<FoodItem> items = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FoodItem {
        @JsonProperty("name_pt")
        public String namePt;
        @JsonProperty("name_en")
        public String nameEn;
        @JsonProperty("quantity_grams")
        public Double quantityGrams;
        @JsonProperty("confidence")
        public Double confidence;
    }
}