package br.com.tcc_bot.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class WhatsAppMediaClient {

    @Value("${WHATSAPP_TOKEN}")
    private String whatsappToken;

    @Value("${GRAPH_API_VERSION:v21.0}")
    private String graphApiVersion;

    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public Media download(String mediaId) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(whatsappToken);

        ResponseEntity<String> metaResp = http.exchange(
                "https://graph.facebook.com/" + graphApiVersion + "/" + mediaId,
                HttpMethod.GET, new HttpEntity<>(h), String.class);

        if (!metaResp.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Falha ao obter metadados da mídia: " + metaResp);
        }
        JsonNode meta = mapper.readTree(metaResp.getBody());
        String url = meta.path("url").asText();
        String mime = meta.path("mime_type").asText("image/jpeg");

        ResponseEntity<byte[]> bin = http.exchange(url, HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        if (!bin.getStatusCode().is2xxSuccessful() || bin.getBody() == null) {
            throw new RuntimeException("Falha no download da mídia: " + bin.getStatusCode());
        }
        return new Media(bin.getBody(), mime);
    }

    public record Media(byte[] bytes, String mimeType) {}
}
