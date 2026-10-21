package com.smartrag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiteParseOcrAdapterServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void convertsAliyunBlockDetailsToLiteParseResults() throws Exception {
        AliyunOcrService aliyunOcrService = mock(AliyunOcrService.class);
        LiteParseOcrAdapterService service = new LiteParseOcrAdapterService(aliyunOcrService);
        byte[] imageBytes = "fake-image".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "page.png", "image/png", imageBytes);

        JsonNode aliyunData = objectMapper.readTree("""
                {
                  "subImages": [
                    {
                      "blockInfo": {
                        "blockDetails": [
                          {
                            "blockContent": "我靠SmartRAG拿到的日常实习",
                            "blockConfidence": 99,
                            "blockPoints": [
                              {"x": 42, "y": 52},
                              {"x": 540, "y": 52},
                              {"x": 540, "y": 92},
                              {"x": 42, "y": 92}
                            ]
                          },
                          {
                            "blockContent": "学习这么快噢直接用",
                            "blockConfidence": 98,
                            "blockPoints": [
                              {"x": 41, "y": 122},
                              {"x": 416, "y": 122},
                              {"x": 416, "y": 162},
                              {"x": 41, "y": 162}
                            ]
                          }
                        ]
                      }
                    }
                  ]
                }
                """);

        when(aliyunOcrService.recognize(imageBytes)).thenReturn(aliyunData);

        Map<String, Object> response = service.recognize(file, "zh", "token-1");

        verify(aliyunOcrService).verifyCallbackToken("token-1");
        List<?> results = assertInstanceOf(List.class, response.get("results"));
        assertEquals(2, results.size());

        Map<?, ?> first = assertInstanceOf(Map.class, results.get(0));
        assertEquals("我靠SmartRAG拿到的日常实习", first.get("text"));
        assertEquals(List.of(42.0, 52.0, 540.0, 92.0), first.get("bbox"));
        assertEquals(0.99, (Double) first.get("confidence"), 0.0001);
    }

    @Test
    void fallsBackToContentWhenBlockDetailsAreMissing() throws Exception {
        AliyunOcrService aliyunOcrService = mock(AliyunOcrService.class);
        LiteParseOcrAdapterService service = new LiteParseOcrAdapterService(aliyunOcrService);
        byte[] imageBytes = "fake-image".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "page.png", "image/png", imageBytes);

        when(aliyunOcrService.recognize(imageBytes)).thenReturn(objectMapper.readTree("""
                {
                  "content": "第一行\\n第二行"
                }
                """));

        Map<String, Object> response = service.recognize(file, null, null);

        List<?> results = assertInstanceOf(List.class, response.get("results"));
        assertEquals(2, results.size());
        Map<?, ?> first = assertInstanceOf(Map.class, results.get(0));
        assertEquals("第一行", first.get("text"));
        assertEquals(List.of(0.0, 0.0, 0.0, 0.0), first.get("bbox"));
    }
}
