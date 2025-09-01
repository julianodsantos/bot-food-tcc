package br.com.tcc_bot.whatsapp;

import br.com.tcc_bot.ai.GeminiVisionClient;
import br.com.tcc_bot.ai.GeminiVisionClient.PlateAnalysis;
import br.com.tcc_bot.ai.GeminiVisionClient.FoodItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

@RestController
@RequestMapping("/webhook")
public class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final WhatsAppApiClient api;
    private final WhatsAppMediaClient mediaClient;
    private final GeminiVisionClient gemini;

    @Value("${WHATSAPP_VERIFY_TOKEN}")
    private String verifyToken;

    public WhatsAppWebhookController(WhatsAppApiClient api,
                                     WhatsAppMediaClient mediaClient,
                                     GeminiVisionClient gemini) {
        this.api = api;
        this.mediaClient = mediaClient;
        this.gemini = gemini;
    }

    // Verificação da Meta (GET)
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && Objects.equals(token, verifyToken)) {
            return ResponseEntity.ok(StringUtils.hasText(challenge) ? challenge : "");
        }
        return ResponseEntity.status(403).body("Forbidden");
    }

    // Recebimento dos eventos (POST)
    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody String rawBody) {
        try {
            JsonNode root = mapper.readTree(rawBody);
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    JsonNode value = change.path("value");
                    for (JsonNode msg : value.path("messages")) {
                        String from = msg.path("from").asText("");
                        String type = msg.path("type").asText("");

                        if ("text".equals(type)) {
                            api.sendText(from, "Olá! Para continuar, me envie uma **FOTO** do seu prato.");
                            continue;
                        }

                        if ("image".equals(type)) {
                            String mediaId = msg.path("image").path("id").asText("");
                            log.info("Imagem recebida | media_id={}", mediaId);
                            try {
                                // 1) baixa a mídia do WhatsApp
                                var media = mediaClient.download(mediaId);

                                // 2) analisa com Gemini
                                PlateAnalysis analysis = gemini.analyzePlate(media.bytes(), media.mimeType());

                                // 3) formata e envia de volta
                                String body = formatAnalysis(analysis);
                                api.sendText(from, body);
                            } catch (Exception e) {
                                log.error("Falha na análise da imagem", e);
                                api.sendText(from, "Não consegui analisar a foto agora. Pode tentar novamente?");
                            }
                            continue;
                        }

                        api.sendText(from, "Por enquanto analiso apenas **fotos**. Envie uma imagem do seu prato.");
                    }
                }
            }
        } catch (Exception e) {
            log.error("Erro ao processar webhook", e);
        }
        return ResponseEntity.ok().build();
    }

    private String formatAnalysis(PlateAnalysis analysis) {
        if (analysis == null || analysis.items == null || analysis.items.isEmpty()) {
            return "Não consegui identificar os itens com segurança. Pode enviar outra foto?";
        }
        StringBuilder sb = new StringBuilder("Identifiquei os seguintes alimentos:\n");
        for (FoodItem it : analysis.items) {
            String grams = it.quantityGrams == null ? "?" : String.valueOf(Math.round(it.quantityGrams));
            sb.append("• ").append(safe(it.name))
                    .append(" — ").append(labelPt(it.portionLabel))
                    .append(" (~").append(grams).append(" g)")
                    .append("\n");
        }
        return sb.toString();
    }

    private String labelPt(String en) {
        return switch (en == null ? "" : en) {
            case "small" -> "Porção pequena";
            case "medium" -> "Porção média";
            case "large" -> "Porção grande";
            default -> "tamanho indefinido";
        };
    }

    private String safe(String s) {
        return s == null ? "item" : s;
    }
}