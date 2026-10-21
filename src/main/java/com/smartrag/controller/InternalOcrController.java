package com.smartrag.controller;

import com.smartrag.service.AliyunOcrService;
import com.smartrag.service.LiteParseOcrAdapterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/internal/ocr")
public class InternalOcrController {

    private static final Logger logger = LoggerFactory.getLogger(InternalOcrController.class);

    private final LiteParseOcrAdapterService liteParseOcrAdapterService;
    private final AliyunOcrService aliyunOcrService;

    public InternalOcrController(LiteParseOcrAdapterService liteParseOcrAdapterService,
                                 AliyunOcrService aliyunOcrService) {
        this.liteParseOcrAdapterService = liteParseOcrAdapterService;
        this.aliyunOcrService = aliyunOcrService;
    }

    @PostMapping(
            path = {"/liteparse", "/liteparse/", "/liteparse/ocr"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Map<String, Object>> recognizeForLiteParse(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "token", required = false) String token) {
        try {
            if (!StringUtils.hasText(file.getOriginalFilename()) && file.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty OCR file");
            }

            return ResponseEntity.ok(liteParseOcrAdapterService.recognize(file, language, token));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            logger.error("LiteParse OCR adapter failed", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        }
    }

    @PostMapping(path = "/aliyun/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> aliyunOcrHealth(
            @RequestParam(value = "token", required = false) String token) {
        aliyunOcrService.verifyCallbackToken(token);
        return ResponseEntity.ok(Map.of(
                "enabled", aliyunOcrService.isEnabled(),
                "configured", aliyunOcrService.isConfigured(),
                "tokenRequired", aliyunOcrService.isCallbackTokenRequired()
        ));
    }
}
