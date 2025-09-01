package br.com.tcc_bot.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
public class WhatsAppApiClient {

    private final RestTemplate http = new RestTemplate();

    @Value("${WHATSAPP_TOKEN}")
    private String whatsappToken;

    @Value("${WHATSAPP_PHONE_NUMBER_ID}")
    private String phoneNumberId;

    @Value("${GRAPH_API_VERSION:v21.0}")
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
}
