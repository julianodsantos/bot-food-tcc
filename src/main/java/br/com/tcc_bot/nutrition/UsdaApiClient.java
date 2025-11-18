package br.com.tcc_bot.nutrition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

@Component
public class UsdaApiClient {

    @Value("${USDA_API_KEY}")
    private String apiKey;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    private static final String NUTRIENT_CALORIES = "208";
    private static final String NUTRIENT_PROTEIN = "203";
    private static final String NUTRIENT_CARBS = "205";
    private static final String NUTRIENT_FAT = "204";

    /**
     * DTO interno para guardar os dados de 100g vindos do USDA
     */
    public static class NutritionalData100g {
        public double calories = 0.0;
        public double protein = 0.0;
        public double carbohydrates = 0.0;
        public double fat = 0.0;
    }


    public Optional<NutritionalData100g> fetchNutritionalData(String foodName) {
        try {
            Optional<String> fdcId = searchForFdcId(foodName);
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

    private Optional<String> searchForFdcId(String foodName) throws IOException, InterruptedException {
        String url = String.format(
                "https://api.nal.usda.gov/fdc/v1/foods/search?api_key=%s&query=%s&pageSize=1",
                apiKey, java.net.URLEncoder.encode(foodName, java.nio.charset.StandardCharsets.UTF_8)
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return Optional.empty();
        }

        JsonNode root = mapper.readTree(resp.body());
        JsonNode foods = root.path("foods");
        if (foods.isMissingNode() || !foods.isArray() || foods.isEmpty()) {
            return Optional.empty();
        }

        return Optional.ofNullable(foods.path(0).path("fdcId").asText(null));
    }

    private Optional<NutritionalData100g> getDetailsByFdcId(String fdcId) throws IOException, InterruptedException {
        String url = String.format(
                "https://api.nal.usda.gov/fdc/v1/food/%s?api_key=%s&nutrients=%s&nutrients=%s&nutrients=%s&nutrients=%s",
                fdcId, apiKey, NUTRIENT_CALORIES, NUTRIENT_PROTEIN, NUTRIENT_CARBS, NUTRIENT_FAT
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return Optional.empty();
        }

        JsonNode root = mapper.readTree(resp.body());
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
    }
}