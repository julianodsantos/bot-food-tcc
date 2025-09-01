package br.com.tcc_bot.ai;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/ai")
public class AiTestController {
    private final GeminiVisionClient gemini;

    public AiTestController(GeminiVisionClient gemini) {
        this.gemini = gemini;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GeminiVisionClient.PlateAnalysis analyze(@RequestPart("image") MultipartFile file) throws Exception {
        String mime = file.getContentType() == null ? "image/jpeg" : file.getContentType();
        return gemini.analyzePlate(file.getBytes(), mime);
    }
}
