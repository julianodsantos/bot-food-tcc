package br.com.tcc_bot.ai;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/ai")
public class AiTestController {

    private final AnalysisService analysisService;

    public AiTestController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AnalysisService.FullAnalysisResponse analyze(@RequestPart("image") MultipartFile file) throws Exception {
        String mime = file.getContentType() == null ? "image/jpeg" : file.getContentType();

        GeminiVisionClient.PlateAnalysis analysis = analysisService.analyzeImage(file.getBytes(), mime);

        return analysisService.calculateNutrients(analysis);
    }
}
