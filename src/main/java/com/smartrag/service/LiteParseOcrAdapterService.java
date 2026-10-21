package com.smartrag.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LiteParseOcrAdapterService {

    private final AliyunOcrService aliyunOcrService;

    public LiteParseOcrAdapterService(AliyunOcrService aliyunOcrService) {
        this.aliyunOcrService = aliyunOcrService;
    }

    public Map<String, Object> recognize(MultipartFile file, String language, String token) throws IOException {
        aliyunOcrService.verifyCallbackToken(token);
        JsonNode data = aliyunOcrService.recognize(file.getBytes());

        List<Map<String, Object>> results = new ArrayList<>();
        JsonNode subImages = pathAny(data, "SubImages", "subImages");
        if (subImages.isArray()) {
            for (JsonNode subImage : subImages) {
                appendBlockResults(results, pathAny(pathAny(subImage, "BlockInfo", "blockInfo"), "BlockDetails", "blockDetails"));
            }
        }

        if (results.isEmpty()) {
            appendContentFallback(results, textAny(data, "Content", "content"));
        }

        results.sort(Comparator
                .comparingDouble((Map<String, Object> item) -> ((List<?>) item.get("bbox")).isEmpty() ? 0.0 : toDouble(((List<?>) item.get("bbox")).get(1)))
                .thenComparingDouble(item -> ((List<?>) item.get("bbox")).isEmpty() ? 0.0 : toDouble(((List<?>) item.get("bbox")).get(0))));

        return Map.of("results", results);
    }

    private void appendBlockResults(List<Map<String, Object>> results, JsonNode blockDetails) {
        if (!blockDetails.isArray()) {
            return;
        }

        for (JsonNode block : blockDetails) {
            String text = textAny(block, "BlockContent", "blockContent").trim();
            if (text.isEmpty()) {
                continue;
            }

            List<Double> bbox = pointsToBbox(pathAny(block, "BlockPoints", "blockPoints"));
            double confidence = normalizeConfidence(numberAny(block, 100.0, "BlockConfidence", "blockConfidence"));
            results.add(result(text, bbox, confidence));
        }
    }

    private void appendContentFallback(List<Map<String, Object>> results, String content) {
        if (content == null || content.isBlank()) {
            return;
        }

        for (String line : content.split("\\R+")) {
            String text = line.trim();
            if (!text.isEmpty()) {
                results.add(result(text, List.of(0.0, 0.0, 0.0, 0.0), 1.0));
            }
        }
    }

    private Map<String, Object> result(String text, List<Double> bbox, double confidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", text);
        result.put("bbox", bbox);
        result.put("confidence", Math.max(0.0, Math.min(1.0, confidence)));
        return result;
    }

    private List<Double> pointsToBbox(JsonNode points) {
        if (!points.isArray() || points.isEmpty()) {
            return List.of(0.0, 0.0, 0.0, 0.0);
        }

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = 0.0;
        double maxY = 0.0;
        for (JsonNode point : points) {
            double x = numberAny(point, 0.0, "X", "x");
            double y = numberAny(point, 0.0, "Y", "y");
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        if (minX == Double.MAX_VALUE || minY == Double.MAX_VALUE) {
            return List.of(0.0, 0.0, 0.0, 0.0);
        }
        return List.of(minX, minY, maxX, maxY);
    }

    private double normalizeConfidence(double confidence) {
        return confidence > 1.0 ? confidence / 100.0 : confidence;
    }

    private double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private JsonNode pathAny(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private String textAny(JsonNode node, String... fieldNames) {
        JsonNode value = pathAny(node, fieldNames);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private double numberAny(JsonNode node, double defaultValue, String... fieldNames) {
        JsonNode value = pathAny(node, fieldNames);
        return value.isMissingNode() || value.isNull() ? defaultValue : value.asDouble(defaultValue);
    }
}
