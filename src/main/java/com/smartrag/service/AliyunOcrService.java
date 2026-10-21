package com.smartrag.service;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextRequest;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextResponse;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.Common;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.util.Objects;

@Service
public class AliyunOcrService {

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String endpoint;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String type;
    private final String outputCoordinate;
    private final boolean outputOricoord;
    private final boolean outputRow;
    private final boolean outputParagraph;
    private final boolean outputTable;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final String callbackToken;

    private volatile Client client;

    public AliyunOcrService(
            ObjectMapper objectMapper,
            @Value("${aliyun.ocr.enabled:false}") boolean enabled,
            @Value("${aliyun.ocr.endpoint:ocr-api.cn-hangzhou.aliyuncs.com}") String endpoint,
            @Value("${aliyun.ocr.access-key-id:${ALIBABA_CLOUD_ACCESS_KEY_ID:}}") String accessKeyId,
            @Value("${aliyun.ocr.access-key-secret:${ALIBABA_CLOUD_ACCESS_KEY_SECRET:}}") String accessKeySecret,
            @Value("${aliyun.ocr.type:Advanced}") String type,
            @Value("${aliyun.ocr.output-coordinate:points}") String outputCoordinate,
            @Value("${aliyun.ocr.output-oricoord:true}") boolean outputOricoord,
            @Value("${aliyun.ocr.output-row:false}") boolean outputRow,
            @Value("${aliyun.ocr.output-paragraph:false}") boolean outputParagraph,
            @Value("${aliyun.ocr.output-table:false}") boolean outputTable,
            @Value("${aliyun.ocr.connect-timeout-millis:10000}") int connectTimeoutMillis,
            @Value("${aliyun.ocr.read-timeout-millis:60000}") int readTimeoutMillis,
            @Value("${aliyun.ocr.callback-token:}") String callbackToken) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.type = type;
        this.outputCoordinate = outputCoordinate;
        this.outputOricoord = outputOricoord;
        this.outputRow = outputRow;
        this.outputParagraph = outputParagraph;
        this.outputTable = outputTable;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.callbackToken = callbackToken;
    }

    public JsonNode recognize(byte[] imageBytes) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Aliyun OCR is disabled");
        }
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Aliyun OCR access key is not configured");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty OCR image");
        }

        try {
            RecognizeAllTextRequest request = new RecognizeAllTextRequest()
                    .setBody(new ByteArrayInputStream(imageBytes))
                    .setType(defaultIfBlank(type, "Advanced"))
                    .setOutputOricoord(outputOricoord);

            if (StringUtils.hasText(outputCoordinate)) {
                request.setOutputCoordinate(outputCoordinate.trim());
            }
            if ("Advanced".equalsIgnoreCase(defaultIfBlank(type, "Advanced"))) {
                request.setAdvancedConfig(new RecognizeAllTextRequest.RecognizeAllTextRequestAdvancedConfig()
                        .setOutputRow(outputRow)
                        .setOutputParagraph(outputParagraph)
                        .setOutputTable(outputTable));
            }

            RuntimeOptions runtimeOptions = new RuntimeOptions()
                    .setConnectTimeout(connectTimeoutMillis)
                    .setReadTimeout(readTimeoutMillis);

            RecognizeAllTextResponse response = getClient().recognizeAllTextWithOptions(request, runtimeOptions);
            if (response == null || response.getBody() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Aliyun OCR returned empty response");
            }
            String code = response.getBody().getCode();
            if (StringUtils.hasText(code) && !"200".equals(code)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Aliyun OCR failed: " + code + " " + response.getBody().getMessage());
            }

            return objectMapper.readTree(Common.toJSONString(response.getBody().getData()));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (TeaException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Aliyun OCR request failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Aliyun OCR request failed: " + e.getMessage(), e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(accessKeyId) && StringUtils.hasText(accessKeySecret);
    }

    public boolean isCallbackTokenRequired() {
        return StringUtils.hasText(callbackToken);
    }

    public void verifyCallbackToken(String token) {
        if (StringUtils.hasText(callbackToken) && !Objects.equals(callbackToken, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid OCR callback token");
        }
    }

    private Client getClient() throws Exception {
        Client current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                Config config = new Config()
                        .setAccessKeyId(accessKeyId)
                        .setAccessKeySecret(accessKeySecret);
                config.endpoint = defaultIfBlank(endpoint, "ocr-api.cn-hangzhou.aliyuncs.com");
                client = new Client(config);
            }
            return client;
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
