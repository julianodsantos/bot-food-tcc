package br.com.tcc_bot.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class WhatsAppApiClient {

    private final RestClient restClient;

    @Value("${WHATSAPP_TOKEN}")
    private String whatsappToken;

    @Value("${WHATSAPP_PHONE_NUMBER_ID}")
    private String phoneNumberId;

    @Value("${GRAPH_API_VERSION:v24.0}")
    private String graphApiVersion;

    public WhatsAppApiClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    private String messagesUrl() {
        return "https://graph.facebook.com/" + graphApiVersion + "/" + phoneNumberId + "/messages";
    }

    public void sendText(String to, String body) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", to);
        payload.put("type", "text");

        Map<String, Object> text = new HashMap<>();
        text.put("preview_url", false);
        text.put("body", body);
        payload.put("text", text);

        doPost(payload);
    }

    public void sendInteractiveButtons(String to, String body, Map<String, String> buttons) {
        List<Map<String, Object>> buttonActions = buttons.entrySet().stream()
                .map(entry -> Map.of(
                        "type", "reply",
                        "reply", Map.of("id", entry.getKey(), "title", entry.getValue())
                ))
                .toList();

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "button",
                        "body", Map.of("text", body),
                        "action", Map.of("buttons", buttonActions)
                )
        );

        doPost(payload);
    }

    public void sendListMessage(String to, String body, String buttonText, Map<String, String> rows) {
        // Monta as linhas (rows)
        List<Map<String, Object>> listRows = new ArrayList<>();
        for (Map.Entry<String, String> entry : rows.entrySet()) {
            listRows.add(Map.of(
                    "id", entry.getKey(),
                    "title", entry.getValue()
            ));
        }

        Map<String, Object> section = Map.of(
                "title", "Opções",
                "rows", listRows
        );

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "body", Map.of("text", body),
                        "action", Map.of(
                                "button", buttonText,
                                "sections", List.of(section)
                        )
                )
        );

        doPost(payload);
    }

    private void doPost(Object payload) {
        restClient.post()
                .uri(messagesUrl())
                .header("Authorization", "Bearer " + whatsappToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }
}