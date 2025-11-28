package br.com.tcc_bot.whatsapp;

import br.com.tcc_bot.ai.AnalysisService;
import br.com.tcc_bot.ai.GeminiVisionClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/webhook")
public class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final Map<String, Long> processedMessages = new ConcurrentHashMap<>();
    private static final long MESSAGE_EXPIRY_MS = TimeUnit.MINUTES.toMillis(10); // 10 minutos

    private final Map<String, GeminiVisionClient.PlateAnalysis> pendingAnalyses = new ConcurrentHashMap<>();
    private final Map<String, String> userEditState = new ConcurrentHashMap<>();

    private final ObjectMapper mapper = new ObjectMapper();
    private final WhatsAppApiClient api;
    private final WhatsAppMediaClient mediaClient;
    private final AnalysisService analysisService;

    @Value("${WHATSAPP_VERIFY_TOKEN}")
    private String verifyToken;

    public WhatsAppWebhookController(WhatsAppApiClient api,
                                     WhatsAppMediaClient mediaClient,
                                     AnalysisService analysisService) {
        this.api = api;
        this.mediaClient = mediaClient;
        this.analysisService = analysisService;
    }

    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode") Optional<String> modeOpt,
            @RequestParam(name = "hub.verify_token") Optional<String> tokenOpt,
            @RequestParam(name = "hub.challenge") Optional<String> challengeOpt) {

        String mode = modeOpt.orElse(null);
        String token = tokenOpt.orElse(null);
        String challenge = challengeOpt.orElse(null);

        if ("subscribe".equals(mode) && Objects.equals(token, verifyToken)) {
            return ResponseEntity.ok(StringUtils.hasText(challenge) ? challenge : "");
        }
        return ResponseEntity.status(403).body("Forbidden");
    }

    /**
     * Ponto de entrada principal para todos os eventos do WhatsApp (POST)
     */
    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody String rawBody) {
        try {
            JsonNode root = mapper.readTree(rawBody);
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    JsonNode value = change.path("value");
                    for (JsonNode msg : value.path("messages")) {

                        String messageId = msg.path("id").asText("");
                        String from = msg.path("from").asText("");
                        String type = msg.path("type").asText("");

                        if (isMessageAlreadyProcessed(messageId)) {
                            log.info("Mensagem duplicada ignorada: {} de {}", messageId, from);
                            continue;
                        }

                        markMessageAsProcessed(messageId);

                        log.info("Processando mensagem {} do tipo {} de {}", messageId, type, from);

                        switch (type) {
                            case "image" -> handleImage(from, msg.path("image").path("id").asText(""));
                            case "text" -> handleText(from, msg.path("text").path("body").asText(""));
                            case "interactive" -> handleInteractive(from, msg.path("interactive"));
                            case null, default ->
                                    api.sendText(from, "Por enquanto analiso apenas *fotos*. Envie uma imagem do seu prato.");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Erro ao processar webhook", e);
        }

        cleanupOldMessages();

        return ResponseEntity.ok().build();
    }

    /**
     * Verifica se uma mensagem já foi processada
     */
    private boolean isMessageAlreadyProcessed(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return false;
        }

        Long timestamp = processedMessages.get(messageId);
        if (timestamp == null) {
            return false;
        }

        return (System.currentTimeMillis() - timestamp) < MESSAGE_EXPIRY_MS;
    }

    /**
     * Marca uma mensagem como processada
     */
    private void markMessageAsProcessed(String messageId) {
        if (StringUtils.hasText(messageId)) {
            processedMessages.put(messageId, System.currentTimeMillis());
        }
    }

    /**
     * Remove mensagens antigas do cache (evita memory leak)
     */
    private void cleanupOldMessages() {
        long now = System.currentTimeMillis();
        processedMessages.entrySet().removeIf(entry ->
                (now - entry.getValue()) > MESSAGE_EXPIRY_MS
        );
    }

    /**
     * Rota 1: Chamado quando o usuário envia uma IMAGEM
     */
    private void handleImage(String from, String mediaId) {
        try {
            log.info("Imagem recebida de {}. media_id={}", from, mediaId);
            api.sendText(from, "Recebi sua foto! Analisando com o Gemini... 🤖");

            var media = mediaClient.download(mediaId);

            GeminiVisionClient.PlateAnalysis analysis = analysisService.analyzeImage(media.bytes(), media.mimeType());

            pendingAnalyses.put(from, analysis);
            userEditState.remove(from);

            String body = formatSimpleAnalysisBody(analysis);
            if (body == null) {
                api.sendText(from, "Não consegui identificar os itens com segurança. Pode enviar outra foto?");
                return;
            }

            api.sendInteractiveButtons(from, body, Map.of(
                    "confirm_analysis", "✅ Confirmar",
                    "edit_analysis", "✏️ Editar"
            ));

        } catch (Exception e) {
            log.error("Falha na análise da imagem (handleImage)", e);
            api.sendText(from, "Não consegui analisar a foto agora. Pode tentar novamente?");
        }
    }

    /**
     * Rota 2: Chamado quando o usuário envia um TEXTO
     */
    private void handleText(String from, String body) {
        String editState = userEditState.get(from);

        if (editState != null) {
            handleWeightEdit(from, editState, body.trim());
        } else {
            api.sendText(from, "Olá! Para começar, me envie uma **FOTO** do seu prato.");
        }
    }

    /**
     * Rota 3: Chamado quando o usuário clica em um BOTÃO ou item de LISTA
     */
    private void handleInteractive(String from, JsonNode interactiveNode) {
        String interactiveType = interactiveNode.path("type").asText("");

        if ("list_reply".equals(interactiveType)) {
            String selectedId = interactiveNode.path("list_reply").path("id").asText("");

            if ("confirm_analysis".equals(selectedId)) {
                handleConfirm(from);
            }
            else if (selectedId.startsWith("edit_item_")) {
                handleEditItem(from, selectedId);
            }
        }
        else if ("button_reply".equals(interactiveType)) {
            String buttonId = interactiveNode.path("button_reply").path("id").asText("");

            switch (buttonId) {
                case "confirm_analysis":
                    handleConfirm(from);
                    break;

                case "edit_analysis":
                    GeminiVisionClient.PlateAnalysis pendingAnalysis = pendingAnalyses.get(from);
                    if (pendingAnalysis != null) {
                        sendUpdatedAnalysisList(from, pendingAnalysis);
                    } else {
                        api.sendText(from, "Sua análise expirou. Envie a foto novamente.");
                    }
                    break;

                default:
                    log.warn("ID de botão desconhecido: {}", buttonId);
                    break;
            }
        }
    }

    /**
     * Chamado quando o usuário clica em um item da lista para editar
     */
    private void handleEditItem(String from, String selectedId) {
        GeminiVisionClient.PlateAnalysis pendingAnalysis = pendingAnalyses.get(from);
        if (pendingAnalysis == null) {
            api.sendText(from, "Sua análise expirou. Envie a foto novamente.");
            return;
        }

        try {
            int itemIndex = Integer.parseInt(selectedId.split("_")[2]);
            if (itemIndex >= pendingAnalysis.items.size()) {
                throw new IndexOutOfBoundsException("Índice do item fora dos limites.");
            }

            GeminiVisionClient.FoodItem item = pendingAnalysis.items.get(itemIndex);
            String currentWeight = "0";
            if (item.quantityGrams != null) {
                currentWeight = String.valueOf(Math.round(item.quantityGrams));
            }

            userEditState.put(from, selectedId);

            api.sendText(from, "Qual o novo peso (em gramas) para *" + item.namePt + "*?\n(Peso atual: ~" + currentWeight + "g)");

        } catch (Exception e) {
            log.error("Erro ao processar handleEditItem, ID: {}", selectedId, e);
            api.sendText(from, "Houve um erro ao selecionar o item. Tente novamente.");
            userEditState.remove(from);
        }
    }

    /**
     * Chamado quando o usuário envia um texto e está no "modo de edição"
     */
    private void handleWeightEdit(String from, String editStateId, String newWeightText) {
        GeminiVisionClient.PlateAnalysis pendingAnalysis = pendingAnalyses.get(from);
        if (pendingAnalysis == null) {
            api.sendText(from, "Sua análise expirou. Envie a foto novamente.");
            userEditState.remove(from);
            return;
        }

        try {
            double newWeight = Double.parseDouble(newWeightText.replace(",", "."));
            int itemIndex = Integer.parseInt(editStateId.split("_")[2]);

            if (itemIndex >= pendingAnalysis.items.size()) {
                throw new IndexOutOfBoundsException("Índice do item fora dos limites.");
            }

            GeminiVisionClient.FoodItem item = pendingAnalysis.items.get(itemIndex);
            String oldName = item.namePt;
            item.quantityGrams = newWeight;

            api.sendText(from, "✅ *" + oldName + "* atualizado para *" + Math.round(newWeight) + "g*.");

            userEditState.remove(from);

            sendUpdatedAnalysisList(from, pendingAnalysis);

        } catch (NumberFormatException e) {
            api.sendText(from, "Formato de peso inválido. Envie apenas o número (ex: `120`).");
        } catch (Exception e) {
            log.error("Erro ao processar handleWeightEdit", e);
            api.sendText(from, "Ocorreu um erro. Vamos tentar de novo.");
            userEditState.remove(from);
        }
    }

    /**
     * Chamado quando o usuário clica em "✅ Confirmar Análise"
     */
    private void handleConfirm(String from) {
        GeminiVisionClient.PlateAnalysis analysisToConfirm = pendingAnalyses.get(from);
        if (analysisToConfirm == null) {
            api.sendText(from, "Não achei nenhuma análise pendente. Envie uma foto primeiro.");
            return;
        }

        try {
            api.sendText(from, "Confirmado! Calculando os nutrientes no USDA... 📊");

            AnalysisService.FullAnalysisResponse nutrition = analysisService.calculateNutrients(analysisToConfirm);

            String fullBody = formatFullAnalysis(nutrition);
            api.sendText(from, fullBody);

            pendingAnalyses.remove(from);
            userEditState.remove(from);

        } catch (Exception e) {
            log.error("Falha ao calcular nutrientes (handleConfirm)", e);
            api.sendText(from, "Tive um problema ao calcular os nutrientes. Tente enviar a foto novamente.");
        }
    }

    /**
     * Apenas formata o corpo da mensagem com a lista de itens e pesos.
     */
    private String formatSimpleAnalysisBody(GeminiVisionClient.PlateAnalysis analysis) {
        if (analysis == null || analysis.items == null || analysis.items.isEmpty()) {
            return null;
        }
        StringBuilder sbBody = new StringBuilder("Identifiquei estes itens:\n\n");
        for (GeminiVisionClient.FoodItem item : analysis.items) {
            String grams = item.quantityGrams == null ? "?" : String.valueOf(Math.round(item.quantityGrams));
            sbBody.append("• *").append(safe(item.namePt)).append("*");
            sbBody.append(" (~").append(grams).append(" g)\n");
        }
        sbBody.append("\nOs pesos estão corretos?");
        return sbBody.toString();
    }

    /**
     * Cria e envia a Lista Interativa (Menu) com os itens e o botão de confirmar
     */
    private void sendUpdatedAnalysisList(String from, GeminiVisionClient.PlateAnalysis analysis) {
        if (analysis == null || analysis.items == null || analysis.items.isEmpty()) {
            api.sendText(from, "Não consegui identificar os itens com segurança. Pode enviar outra foto?");
            return;
        }

        StringBuilder sbBody = new StringBuilder("Identifiquei estes itens:\n\n");
        for (GeminiVisionClient.FoodItem item : analysis.items) {
            String grams = item.quantityGrams == null ? "?" : String.valueOf(Math.round(item.quantityGrams));
            sbBody.append("• *").append(safe(item.namePt)).append("*");
            sbBody.append(" (~").append(grams).append(" g)\n");
        }
        sbBody.append("\nClique em um item abaixo para editar o peso, ou confirme a análise.");
        String body = sbBody.toString();

        Map<String, String> rows = new LinkedHashMap<>();

        final int WHATSAPP_TITLE_LIMIT = 24;

        for (int i = 0; i < analysis.items.size(); i++) {
            GeminiVisionClient.FoodItem item = analysis.items.get(i);

            String title = safe(item.namePt);

            if (title.length() > WHATSAPP_TITLE_LIMIT) {
                int lastSpaceIndex = title.lastIndexOf(' ', WHATSAPP_TITLE_LIMIT - 1);

                if (lastSpaceIndex > 0) {
                    title = title.substring(0, lastSpaceIndex) + "...";
                } else {
                    title = title.substring(0, WHATSAPP_TITLE_LIMIT - 3) + "...";
                }
            }
            rows.put("edit_item_" + i, title);
        }

        rows.put("confirm_analysis", "✅ Confirmar Análise");

        api.sendListMessage(from, body, "Editar ou Confirmar", rows);
    }

    /**
     * Formata a resposta COMPLETA (com nutrientes)
     */
    private String formatFullAnalysis(AnalysisService.FullAnalysisResponse analysis) {
        if (analysis == null || analysis.items == null || analysis.items.isEmpty()) {
            return "Não consegui calcular. Tente novamente.";
        }

        StringBuilder sb = new StringBuilder("Aqui está a sua análise nutricional:\n\n");

        for (AnalysisService.EnrichedFoodItem it : analysis.items) {
            String grams = it.quantityGrams == null ? "?" : String.valueOf(Math.round(it.quantityGrams));

            sb.append("• *").append(safe(it.name)).append("*");
            sb.append(" (~").append(grams).append(" g)\n");

            if (it.calories > 0) {
                sb.append(String.format("  Calorias: %.0f kcal\n", it.calories));
                sb.append(String.format("  Carboidratos: %.1fg | Proteínas: %.1fg | Gorduras: %.1fg\n",
                        it.carbohydrates, it.protein, it.fat));
            } else {
                sb.append("  _(Não foi possível calcular os nutrientes para este item)_\n");
            }
            sb.append("\n");
        }

        AnalysisService.NutritionalTotals totals = analysis.totals;
        sb.append("----------------------------\n");
        sb.append("*TOTAL DO PRATO*:\n");
        sb.append(String.format("• Calorias: *%.0f kcal*\n", totals.totalCalories));
        sb.append(String.format("• Carboidratos: %.1f g\n", totals.totalCarbs));
        sb.append(String.format("• Proteínas: %.1f g\n", totals.totalProtein));
        sb.append(String.format("• Gorduras: %.1f g\n", totals.totalFat));

        return sb.toString();
    }

    /**
     * Helper para evitar NullPointerException em nomes de itens
     */
    private String safe(String s) {
        return s == null ? "item" : s;
    }
}