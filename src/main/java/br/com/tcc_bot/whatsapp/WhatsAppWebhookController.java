package br.com.tcc_bot.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhook")
public class WhatsAppWebhookController {

    private final String verifyToken;

    public WhatsAppWebhookController(@Value("${WHATSAPP_VERIFY_TOKEN}") String verifyToken) {
        this.verifyToken = verifyToken;
    }

    // Verificação do webhook (Meta chama com hub.mode, hub.verify_token, hub.challenge)
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {

        if ("subscribe".equals(mode) && verifyToken != null && verifyToken.equals(token)) {
            return ResponseEntity.ok(challenge != null ? challenge : "");
        }
        return ResponseEntity.status(403).body("forbidden");
    }

    // Recepção de eventos (apenas confirma com 200)
    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody(required = false) byte[] ignored) {
        return ResponseEntity.ok().build();
    }
}