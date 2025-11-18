package br.com.tcc_bot.ai;

import br.com.tcc_bot.nutrition.UsdaApiClient;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class AnalysisService {

    private final GeminiVisionClient geminiClient;
    private final UsdaApiClient usdaClient;

    public AnalysisService(GeminiVisionClient geminiClient, UsdaApiClient usdaClient) {
        this.geminiClient = geminiClient;
        this.usdaClient = usdaClient;
    }

    /**
     * ETAPA 1: Chama o Gemini para identificar alimentos e pesos.
     */
    public GeminiVisionClient.PlateAnalysis analyzeImage(byte[] imageBytes, String mimeType) throws Exception {
        return geminiClient.analyzePlate(imageBytes, mimeType);
    }

    /**
     * ETAPA 2: Recebe uma análise (original ou editada) e calcula os nutrientes.
     */
    public FullAnalysisResponse calculateNutrients(GeminiVisionClient.PlateAnalysis plateAnalysis) {

        List<EnrichedFoodItem> enrichedItems = new ArrayList<>();
        double totalCalories = 0.0;
        double totalProteins = 0.0;
        double totalCarbs = 0.0;
        double totalFats = 0.0;

        for (GeminiVisionClient.FoodItem item : plateAnalysis.items) {

            Optional<UsdaApiClient.NutritionalData100g> data100gOpt = usdaClient.fetchNutritionalData(item.nameEn);

            EnrichedFoodItem enrichedItem = new EnrichedFoodItem(item);

            if (data100gOpt.isPresent()) {
                UsdaApiClient.NutritionalData100g data100g = data100gOpt.get();

                double ratio = Optional.ofNullable(item.quantityGrams).orElse(0.0) / 100.0;

                enrichedItem.calories = data100g.calories * ratio;
                enrichedItem.protein = data100g.protein * ratio;
                enrichedItem.carbohydrates = data100g.carbohydrates * ratio;
                enrichedItem.fat = data100g.fat * ratio;

                totalCalories += enrichedItem.calories;
                totalProteins += enrichedItem.protein;
                totalCarbs += enrichedItem.carbohydrates;
                totalFats += enrichedItem.fat;
            } else {
                System.err.println("Nutrientes não encontrados para: " + item.namePt + " (buscado como: '" + item.nameEn + "')");
            }
            enrichedItems.add(enrichedItem);
        }

        FullAnalysisResponse response = new FullAnalysisResponse();
        response.items = enrichedItems;
        response.totals = new NutritionalTotals(totalCalories, totalProteins, totalCarbs, totalFats);

        return response;
    }


    // --- Classes de Resposta Final (DTOs) ---

    public static class FullAnalysisResponse {
        @JsonProperty("items")
        public List<EnrichedFoodItem> items;
        @JsonProperty("totals")
        public NutritionalTotals totals;
    }

    public static class EnrichedFoodItem {
        @JsonProperty("name")
        public String name;
        @JsonProperty("quantity_grams")
        public Double quantityGrams;
        @JsonProperty("confidence")
        public Double confidence;
        @JsonProperty("calories_kcal")
        public double calories = 0.0;
        @JsonProperty("protein_g")
        public double protein = 0.0;
        @JsonProperty("carbohydrates_g")
        public double carbohydrates = 0.0;
        @JsonProperty("fat_g")
        public double fat = 0.0;

        public EnrichedFoodItem(GeminiVisionClient.FoodItem geminiItem) {
            this.name = geminiItem.namePt;
            this.quantityGrams = geminiItem.quantityGrams;
            this.confidence = geminiItem.confidence;
        }
    }

    public static class NutritionalTotals {
        @JsonProperty("total_calories_kcal")
        public double totalCalories;
        @JsonProperty("total_protein_g")
        public double totalProtein;
        @JsonProperty("total_carbohydrates_g")
        public double totalCarbs;
        @JsonProperty("total_fat_g")
        public double totalFat;

        public NutritionalTotals(double cal, double prot, double carb, double fat) {
            this.totalCalories = cal;
            this.totalProtein = prot;
            this.totalCarbs = carb;
            this.totalFat = fat;
        }
    }
}

