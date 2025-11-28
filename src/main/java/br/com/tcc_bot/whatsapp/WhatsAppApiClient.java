package br.com.tcc_bot.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class WhatsAppApiClient {

    private final RestTemplate http = new RestTemplate();

    @Value("${WHATSAPP_TOKEN}")
    private String whatsappToken;

    @Value("${WHATSAPP_PHONE_NUMBER_ID}")
    private String phoneNumberId;

    @Value("${GRAPH_API_VERSION:v24.0}")
    private String graphApiVersion;

    private String messagesUrl() {
        return "https://graph.facebook.com/" + graphApiVersion + "/" + phoneNumberId + "/messages";
    }

    public void sendText(String to, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(whatsappToken);
        h.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", to);
        payload.put("type", "text");

        Map<String, Object> text = new HashMap<>();
        text.put("preview_url", false);
        text.put("body", body);
        payload.put("text", text);

        http.postForEntity(messagesUrl(), new HttpEntity<>(payload, h), String.class);
    }

    public void sendInteractiveButtons(String to, String body, Map<String, String> buttons) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(whatsappToken);
        h.setContentType(MediaType.APPLICATION_JSON);

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

        http.postForEntity(messagesUrl(), new HttpEntity<>(payload, h), String.class);
    }

    public void sendListMessage(String to, String body, String buttonText, Map<String, String> rows) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(whatsappToken);
        h.setContentType(MediaType.APPLICATION_JSON);

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

        http.postForEntity(messagesUrl(), new HttpEntity<>(payload, h), String.class);
    }
}

