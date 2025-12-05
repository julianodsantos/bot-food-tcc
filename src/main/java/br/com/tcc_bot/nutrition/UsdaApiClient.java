package br.com.tcc_bot.nutrition;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
public class UsdaApiClient {

    @Value("${USDA_API_KEY}")
    private String apiKey;

    private final RestClient restClient;

    private static final String NUTRIENT_CALORIES = "208";
    private static final String NUTRIENT_PROTEIN = "203";
    private static final String NUTRIENT_CARBS = "205";
    private static final String NUTRIENT_FAT = "204";

    public UsdaApiClient(RestClient.Builder builder) {
        this.restClient = builder.baseUrl("https://api.nal.usda.gov/fdc/v1").build();
    }

    public static class NutritionalData100g {
        public double calories = 0.0;
        public double protein = 0.0;
        public double carbohydrates = 0.0;
        public double fat = 0.0;
    }

    @Cacheable("usda_foods")
    public Optional<NutritionalData100g> fetchNutritionalData(String foodName) {
        try {
            String sanitizedName = sanitize(foodName);
            Optional<String> fdcId = searchForFdcId(sanitizedName);

            if (fdcId.isEmpty() && sanitizedName.contains(",")) {
                String simpleName = sanitizedName.split(",")[0].trim();
                System.out.println("USDA: Tentando fallback simplificado para: " + simpleName);
                fdcId = searchForFdcId(simpleName);
            }

            if (fdcId.isEmpty()) {
                System.err.println("USDA: Não foi encontrado FDC-ID para: " + foodName);
                return Optional.empty();
            }

            return getDetailsByFdcId(fdcId.get());

        } catch (Exception e) {
            System.err.println("Falha ao buscar dados do USDA para: " + foodName + " - Erro: " + e.getMessage());
            return Optional.empty();
        }
    }

    private String sanitize(String input) {
        if (input == null) return "";
        return input.replace("\"", "")
                .replace("'", "")
                .replace("%", "")
                .trim();
    }

    private Optional<String> searchForFdcId(String foodName) {
        try {
            JsonNode root = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/foods/search")
                            .queryParam("api_key", apiKey)
                            .queryParam("query", foodName)
                            .queryParam("pageSize", 1)
                            // dataType Foundation e SR Legacy são mais confiáveis para alimentos in natura
                            .queryParam("dataType", "Foundation,SR Legacy")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null) return Optional.empty();

            JsonNode foods = root.path("foods");
            if (foods.isMissingNode() || !foods.isArray() || foods.isEmpty()) {
                return Optional.empty();
            }

            return Optional.ofNullable(foods.path(0).path("fdcId").asText(null));

        } catch (Exception e) {
            System.err.println("Erro na chamada de busca USDA (" + foodName + "): " + e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<NutritionalData100g> getDetailsByFdcId(String fdcId) {
        try {
            JsonNode root = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/food/{fdcId}")
                            .queryParam("api_key", apiKey)
                            .queryParam("nutrients", NUTRIENT_CALORIES, NUTRIENT_PROTEIN, NUTRIENT_CARBS, NUTRIENT_FAT)
                            .build(fdcId))
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null) return Optional.empty();

            JsonNode nutrientsNode = root.path("foodNutrients");
            if (nutrientsNode.isMissingNode() || !nutrientsNode.isArray()) {
                return Optional.empty();
            }

            NutritionalData100g data = new NutritionalData100g();
            for (JsonNode nutrientNode : nutrientsNode) {
                String nutrientNumber = nutrientNode.path("nutrient").path("number").asText();
                double amount = nutrientNode.path("amount").asDouble(0.0);

                switch (nutrientNumber) {
                    case NUTRIENT_CALORIES -> data.calories = amount;
                    case NUTRIENT_PROTEIN -> data.protein = amount;
                    case NUTRIENT_CARBS -> data.carbohydrates = amount;
                    case NUTRIENT_FAT -> data.fat = amount;
                }
            }
            return Optional.of(data);
        } catch (Exception e) {
            System.err.println("Erro ao buscar detalhes do FDC ID " + fdcId + ": " + e.getMessage());
            return Optional.empty();
        }
    }
}