package br.com.tcc_bot.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WhatsAppMediaClient {

    @Value("${WHATSAPP_TOKEN}")
    private String whatsappToken;

    @Value("${GRAPH_API_VERSION:v24.0}")
    private String graphApiVersion;

    private final RestClient restClient;
    private final ObjectMapper mapper; // Injetamos o Jackson ObjectMapper

    public WhatsAppMediaClient(RestClient.Builder builder, ObjectMapper mapper) {
        this.restClient = builder.build();
        this.mapper = mapper;
    }

    public Media download(String mediaId) {
        try {
            String jsonBody = restClient.get()
                    .uri("https://graph.facebook.com/" + graphApiVersion + "/" + mediaId)
                    .header("Authorization", "Bearer " + whatsappToken)
                    .retrieve()
                    .body(String.class);

            if (jsonBody == null) {
                throw new RuntimeException("Falha ao obter metadados da mídia: resposta vazia");
            }

            JsonNode meta = mapper.readTree(jsonBody);

            String url = meta.path("url").asText();
            String mime = meta.path("mime_type").asText("image/jpeg");

            byte[] bytes = restClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + whatsappToken)
                    .retrieve()
                    .body(byte[].class);

            if (bytes == null) {
                throw new RuntimeException("Falha no download da mídia: corpo vazio");
            }

            return new Media(bytes, mime);

        } catch (Exception e) {
            throw new RuntimeException("Erro ao baixar mídia do WhatsApp: " + e.getMessage(), e);
        }
    }

    public record Media(byte[] bytes, String mimeType) {}
}