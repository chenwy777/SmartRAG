package com.smartrag.service;

import com.smartrag.exception.CustomException;
import com.smartrag.model.ModelProviderConfig;
import com.smartrag.repository.ModelProviderConfigRepository;
import com.smartrag.utils.SecretCryptoService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class ModelProviderConfigService {

    public static final String SCOPE_LLM = "llm";
    public static final String SCOPE_EMBEDDING = "embedding";
    public static final String API_STYLE_OPENAI = "openai-compatible";
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String EMBEDDINGS_PATH = "/embeddings";

    private final ModelProviderConfigRepository repository;
    private final SecretCryptoService secretCryptoService;
    private volatile ModelProviderSettingsView currentSettings;

    @Value("${deepseek.api.url:https://api.deepseek.com/v1}")
    private String deepSeekApiUrl;

    @Value("${deepseek.api.key:}")
    private String deepSeekApiKey;

    @Value("${deepseek.api.model:deepseek-chat}")
    private String deepSeekModel;

    @Value("${embedding.api.url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String embeddingApiUrl;

    @Value("${embedding.api.key:}")
    private String embeddingApiKey;

    @Value("${embedding.api.model:text-embedding-v4}")
    private String embeddingModel;

    @Value("${embedding.api.dimension:2048}")
    private Integer embeddingDimension;

    public ModelProviderConfigService(ModelProviderConfigRepository repository, SecretCryptoService secretCryptoService) {
        this.repository = repository;
        this.secretCryptoService = secretCryptoService;
        this.currentSettings = buildDefaultSettings();
    }

    @PostConstruct
    public void loadPersistedConfigs() {
        reloadSettings();
    }

    public ModelProviderSettingsView getCurrentSettings() {
        return currentSettings;
    }

    public ActiveProviderView getActiveProvider(String scope) {
        ScopeSettingsView settings = resolveScope(scope, currentSettings);
        return settings.providers().stream()
                .filter(ProviderConfigView::active)
                .findFirst()
                .map(this::toActiveProvider)
                .orElseThrow(() -> new CustomException("未找到激活的模型配置: " + scope, HttpStatus.INTERNAL_SERVER_ERROR));
    }

    public synchronized ScopeSettingsView updateScope(String scope, UpdateScopeRequest request, String updatedBy) {
        String normalizedScope = normalizeScope(scope);
        validateUpdateRequest(normalizedScope, request);

        ScopeSettingsView existingScope = resolveScope(normalizedScope, currentSettings);
        Map<String, ProviderConfigView> existingMap = toProviderMap(existingScope.providers());
        String currentActiveProvider = existingScope.activeProvider();
        ProviderConfigView currentActiveConfig = existingMap.get(currentActiveProvider);

        if (SCOPE_EMBEDDING.equals(normalizedScope) && !Objects.equals(request.activeProvider(), currentActiveProvider)) {
            ProviderUpsertRequest target = findProviderRequest(request.providers(), request.activeProvider());
            if (target != null && currentActiveConfig != null && requiresEmbeddingReindex(currentActiveConfig, target)) {
                throw new CustomException("Embedding 模型切换需要重嵌入任务，当前版本不支持直接切换 active provider", HttpStatus.CONFLICT);
            }
        }

        List<ModelProviderConfig> persistedScopeConfigs = repository.findByConfigScopeOrderByProviderCodeAsc(normalizedScope);
        Map<String, ModelProviderConfig> persistedMap = new LinkedHashMap<>();
        for (ModelProviderConfig config : persistedScopeConfigs) {
            persistedMap.put(config.getProviderCode(), config);
        }

        for (ProviderUpsertRequest item : request.providers()) {
            String provider = normalizeProvider(item.provider());
            ProviderConfigView fallback = existingMap.get(provider);
            if (fallback == null) {
                throw new CustomException("不支持的 provider: " + provider, HttpStatus.BAD_REQUEST);
            }

            ModelProviderConfig entity = persistedMap.getOrDefault(provider, new ModelProviderConfig());
            entity.setConfigScope(normalizedScope);
            entity.setProviderCode(provider);
            entity.setDisplayName(fallback.displayName());
            entity.setApiStyle(fallback.apiStyle());
            entity.setApiBaseUrl(normalizeOpenAiCompatibleBaseUrl(
                    requireNonBlank(item.apiBaseUrl(), fallback.apiBaseUrl(), provider + " API 地址不能为空")));
            entity.setModelName(requireNonBlank(item.model(), fallback.model(), provider + " 模型不能为空"));
            entity.setEmbeddingDimension(SCOPE_EMBEDDING.equals(normalizedScope)
                    ? Optional.ofNullable(item.dimension()).orElse(fallback.dimension())
                    : null);
            entity.setEnabled(item.enabled() == null ? fallback.enabled() : item.enabled());
            entity.setActive(provider.equals(request.activeProvider()));
            entity.setUpdatedBy(updatedBy);
            entity.setApiKeyCiphertext(resolveCiphertext(item.apiKey(), fallback));
            repository.save(entity);
            persistedMap.put(provider, entity);
        }

        for (ModelProviderConfig entity : persistedMap.values()) {
            boolean shouldBeActive = entity.getProviderCode().equals(request.activeProvider());
            if (entity.isActive() != shouldBeActive) {
                entity.setActive(shouldBeActive);
                entity.setUpdatedBy(updatedBy);
                repository.save(entity);
            }
        }

        reloadSettings();
        return resolveScope(normalizedScope, currentSettings);
    }

    public ConnectivityTestView testConnection(String scope, ProviderConnectionTestRequest request) {
        String normalizedScope = normalizeScope(scope);
        validateConnectionTestRequest(normalizedScope, request);

        long startAt = System.currentTimeMillis();
        String provider = normalizeOptionalProvider(request.provider());
        try {
            WebClient.Builder builder = WebClient.builder()
                    .baseUrl(normalizeOpenAiCompatibleBaseUrl(request.apiBaseUrl()))
                    .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);

            String apiKey = resolveConnectionTestApiKey(normalizedScope, provider, request.apiKey());
            if (apiKey != null && !apiKey.isBlank()) {
                builder.defaultHeader("Authorization", "Bearer " + apiKey);
            }

            WebClient client = builder.build();
            if (SCOPE_LLM.equals(normalizedScope)) {
                Map<String, Object> payload = Map.of(
                        "model", request.model(),
                        "messages", List.of(Map.of("role", "user", "content", "ping")),
                        "stream", false,
                        "max_tokens", 1
                );
                client.post()
                        .uri("/chat/completions")
                        .bodyValue(payload)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(8));
            } else {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("model", request.model());
                payload.put("input", List.of("ping"));
                payload.put("encoding_format", "float");
                if (request.dimension() != null) {
                    payload.put("dimension", request.dimension());
                }
                client.post()
                        .uri("/embeddings")
                        .bodyValue(payload)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(8));
            }

            return new ConnectivityTestView(true, "连接成功", System.currentTimeMillis() - startAt);
        } catch (Exception exception) {
            return new ConnectivityTestView(false, formatConnectionFailure(provider, exception), System.currentTimeMillis() - startAt);
        }
    }

    public synchronized void reloadSettings() {
        this.currentSettings = mergeOverrides(buildDefaultSettings(), repository.findAll());
    }

    private ModelProviderSettingsView buildDefaultSettings() {
        ScopeSettingsView llm = new ScopeSettingsView(
                SCOPE_LLM,
                "deepseek",
                List.of(
                        new ProviderConfigView("deepseek", "DeepSeek", API_STYLE_OPENAI, deepSeekApiUrl, deepSeekModel, null, true, true, hasValue(deepSeekApiKey), secretCryptoService.mask(deepSeekApiKey)),
                        new ProviderConfigView("qwen", "Qwen", API_STYLE_OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash", null, true, false, false, ""),
                        new ProviderConfigView("zhipu", "ZhipuAI", API_STYLE_OPENAI, "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-air", null, true, false, false, "")
                )
        );
        ScopeSettingsView embedding = new ScopeSettingsView(
                SCOPE_EMBEDDING,
                "aliyun",
                List.of(
                        new ProviderConfigView("aliyun", "阿里云", API_STYLE_OPENAI, embeddingApiUrl, embeddingModel, embeddingDimension, true, true, hasValue(embeddingApiKey), secretCryptoService.mask(embeddingApiKey)),
                        new ProviderConfigView("zhipu", "智谱AI", API_STYLE_OPENAI, "https://open.bigmodel.cn/api/paas/v4", "embedding-3", 2048, true, false, false, "")
                )
        );
        return new ModelProviderSettingsView(llm, embedding);
    }

    private ModelProviderSettingsView mergeOverrides(ModelProviderSettingsView defaults, List<ModelProviderConfig> configs) {
        ScopeSettingsView llm = mergeScope(defaults.llm(), configs);
        ScopeSettingsView embedding = mergeScope(defaults.embedding(), configs);
        return new ModelProviderSettingsView(llm, embedding);
    }

    private ScopeSettingsView mergeScope(ScopeSettingsView defaults, List<ModelProviderConfig> configs) {
        Map<String, ProviderConfigView> merged = toProviderMap(defaults.providers());
        String activeProvider = defaults.activeProvider();

        for (ModelProviderConfig config : configs) {
            if (!defaults.scope().equals(config.getConfigScope())) {
                continue;
            }

            ProviderConfigView fallback = merged.get(config.getProviderCode());
            if (fallback == null) {
                continue;
            }

            String decryptedApiKey = secretCryptoService.decrypt(config.getApiKeyCiphertext());
            merged.put(config.getProviderCode(), new ProviderConfigView(
                    config.getProviderCode(),
                    config.getDisplayName(),
                    config.getApiStyle(),
                    normalizeOpenAiCompatibleBaseUrl(config.getApiBaseUrl()),
                    config.getModelName(),
                    config.getEmbeddingDimension() != null ? config.getEmbeddingDimension() : fallback.dimension(),
                    config.isEnabled(),
                    config.isActive(),
                    hasValue(decryptedApiKey),
                    secretCryptoService.mask(decryptedApiKey)
            ));

            if (config.isActive()) {
                activeProvider = config.getProviderCode();
            }
        }

        List<ProviderConfigView> providers = new ArrayList<>(merged.values());
        providers.sort(Comparator.comparing(ProviderConfigView::provider));
        return new ScopeSettingsView(defaults.scope(), activeProvider, providers);
    }

    private ActiveProviderView toActiveProvider(ProviderConfigView provider) {
        String apiKey = null;
        Optional<ModelProviderConfig> persisted = repository.findByConfigScopeAndProviderCode(resolveScopeByProvider(provider.provider()), provider.provider());
        if (persisted.isPresent()) {
            apiKey = secretCryptoService.decrypt(persisted.get().getApiKeyCiphertext());
        } else if ("deepseek".equals(provider.provider())) {
            apiKey = deepSeekApiKey;
        } else if ("aliyun".equals(provider.provider())) {
            apiKey = embeddingApiKey;
        }

        return new ActiveProviderView(
                provider.provider(),
                provider.displayName(),
                provider.apiStyle(),
                normalizeOpenAiCompatibleBaseUrl(provider.apiBaseUrl()),
                provider.model(),
                apiKey,
                provider.dimension()
        );
    }

    private String resolveScopeByProvider(String provider) {
        ScopeSettingsView llm = currentSettings.llm();
        if (llm.providers().stream().anyMatch(item -> item.provider().equals(provider))) {
            return SCOPE_LLM;
        }
        return SCOPE_EMBEDDING;
    }

    private boolean requiresEmbeddingReindex(ProviderConfigView current, ProviderUpsertRequest target) {
        if (!Objects.equals(current.provider(), normalizeProvider(target.provider()))) {
            return true;
        }
        if (!Objects.equals(current.model(), target.model())) {
            return true;
        }
        return !Objects.equals(current.dimension(), target.dimension());
    }

    private Map<String, ProviderConfigView> toProviderMap(List<ProviderConfigView> providers) {
        Map<String, ProviderConfigView> result = new LinkedHashMap<>();
        for (ProviderConfigView provider : providers) {
            result.put(provider.provider(), provider);
        }
        return result;
    }

    private ProviderUpsertRequest findProviderRequest(List<ProviderUpsertRequest> providers, String provider) {
        return providers.stream()
                .filter(item -> normalizeProvider(item.provider()).equals(provider))
                .findFirst()
                .orElse(null);
    }

    private void validateUpdateRequest(String scope, UpdateScopeRequest request) {
        if (request == null || request.providers() == null || request.providers().isEmpty()) {
            throw new CustomException("模型配置不能为空", HttpStatus.BAD_REQUEST);
        }
        String activeProvider = normalizeProvider(request.activeProvider());
        boolean activeExists = false;
        boolean activeEnabled = false;
        for (ProviderUpsertRequest provider : request.providers()) {
            String providerCode = normalizeProvider(provider.provider());
            if (providerCode.equals(activeProvider)) {
                activeExists = true;
                activeEnabled = provider.enabled() == null || provider.enabled();
            }
            if (provider.apiBaseUrl() == null || provider.apiBaseUrl().isBlank()) {
                throw new CustomException(providerCode + " API 地址不能为空", HttpStatus.BAD_REQUEST);
            }
            if (provider.model() == null || provider.model().isBlank()) {
                throw new CustomException(providerCode + " 模型不能为空", HttpStatus.BAD_REQUEST);
            }
            if (SCOPE_EMBEDDING.equals(scope) && provider.dimension() != null && provider.dimension() <= 0) {
                throw new CustomException(providerCode + " Embedding 维度必须大于 0", HttpStatus.BAD_REQUEST);
            }
        }

        if (!activeExists) {
            throw new CustomException("激活 provider 不在配置列表中", HttpStatus.BAD_REQUEST);
        }
        if (!activeEnabled) {
            throw new CustomException("激活 provider 必须处于启用状态", HttpStatus.BAD_REQUEST);
        }
    }

    private void validateConnectionTestRequest(String scope, ProviderConnectionTestRequest request) {
        if (request == null) {
            throw new CustomException("连接测试参数不能为空", HttpStatus.BAD_REQUEST);
        }
        if (request.apiBaseUrl() == null || request.apiBaseUrl().isBlank()) {
            throw new CustomException("API 地址不能为空", HttpStatus.BAD_REQUEST);
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new CustomException("模型不能为空", HttpStatus.BAD_REQUEST);
        }
        if (SCOPE_EMBEDDING.equals(scope) && request.dimension() != null && request.dimension() <= 0) {
            throw new CustomException("Embedding 维度必须大于 0", HttpStatus.BAD_REQUEST);
        }
        if (request.provider() != null && !request.provider().isBlank()) {
            resolveProvider(scope, normalizeProvider(request.provider()), currentSettings);
        }
    }

    private ScopeSettingsView resolveScope(String scope, ModelProviderSettingsView settings) {
        String normalizedScope = normalizeScope(scope);
        return SCOPE_LLM.equals(normalizedScope) ? settings.llm() : settings.embedding();
    }

    private ProviderConfigView resolveProvider(String scope, String provider, ModelProviderSettingsView settings) {
        return resolveScope(scope, settings).providers().stream()
                .filter(item -> item.provider().equals(provider))
                .findFirst()
                .orElseThrow(() -> new CustomException("不支持的 provider: " + provider, HttpStatus.BAD_REQUEST));
    }

    private String normalizeScope(String scope) {
        String normalized = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        if (!SCOPE_LLM.equals(normalized) && !SCOPE_EMBEDDING.equals(normalized)) {
            throw new CustomException("不支持的模型作用域: " + scope, HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new CustomException("provider 不能为空", HttpStatus.BAD_REQUEST);
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeOptionalProvider(String provider) {
        return provider == null || provider.isBlank() ? null : normalizeProvider(provider);
    }

    public static String normalizeOpenAiCompatibleBaseUrl(String rawBaseUrl) {
        if (rawBaseUrl == null) {
            return null;
        }
        String normalized = rawBaseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        boolean changed;
        do {
            changed = false;
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (lower.endsWith(CHAT_COMPLETIONS_PATH)) {
                normalized = normalized.substring(0, normalized.length() - CHAT_COMPLETIONS_PATH.length());
                changed = true;
            } else if (lower.endsWith(EMBEDDINGS_PATH)) {
                normalized = normalized.substring(0, normalized.length() - EMBEDDINGS_PATH.length());
                changed = true;
            }
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
                changed = true;
            }
        } while (changed);
        return normalized;
    }

    private String resolveConnectionTestApiKey(String scope, String provider, String rawApiKey) {
        if (rawApiKey != null && !rawApiKey.isBlank()) {
            return rawApiKey.trim();
        }
        if (provider == null) {
            return null;
        }

        ProviderConfigView config = resolveProvider(scope, provider, currentSettings);
        if (!config.hasApiKey()) {
            return null;
        }

        Optional<ModelProviderConfig> persisted = repository.findByConfigScopeAndProviderCode(scope, provider);
        if (persisted.isPresent()) {
            return secretCryptoService.decrypt(persisted.get().getApiKeyCiphertext());
        }
        if ("deepseek".equals(provider)) {
            return deepSeekApiKey;
        }
        if ("aliyun".equals(provider)) {
            return embeddingApiKey;
        }
        return null;
    }

    private String formatConnectionFailure(String provider, Exception exception) {
        WebClientResponseException responseException = findResponseException(exception);
        if (responseException == null) {
            return exception.getMessage();
        }

        int statusCode = responseException.getStatusCode().value();
        String displayName = provider != null ? resolveProviderDisplayName(provider) : "模型";
        if (statusCode == HttpStatus.UNAUTHORIZED.value() || statusCode == HttpStatus.FORBIDDEN.value()) {
            return displayName + " API Key 无效或未填写，请检查已保存密钥，或在“新 API Key”中重新输入后再测试";
        }
        if (statusCode == HttpStatus.NOT_FOUND.value()) {
            return displayName + " API 地址或接口路径不可用，请检查 Base URL 是否填写到 OpenAI 兼容根地址";
        }
        if (statusCode == HttpStatus.BAD_REQUEST.value()) {
            return displayName + " 请求参数无效，请检查模型标识是否为服务商支持的 API 模型名";
        }
        return responseException.getMessage();
    }

    private WebClientResponseException findResponseException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException) {
                return responseException;
            }
            current = current.getCause();
        }
        return null;
    }

    private String resolveProviderDisplayName(String provider) {
        return currentSettings.llm().providers().stream()
                .filter(item -> item.provider().equals(provider))
                .findFirst()
                .or(() -> currentSettings.embedding().providers().stream()
                        .filter(item -> item.provider().equals(provider))
                        .findFirst())
                .map(ProviderConfigView::displayName)
                .orElse(provider);
    }

    private String resolveCiphertext(String rawApiKey, ProviderConfigView fallback) {
        if (rawApiKey != null && !rawApiKey.isBlank()) {
            return secretCryptoService.encrypt(rawApiKey.trim());
        }
        if (!fallback.hasApiKey()) {
            return null;
        }
        Optional<ModelProviderConfig> persisted = repository.findByConfigScopeAndProviderCode(resolveScopeByProvider(fallback.provider()), fallback.provider());
        return persisted.map(ModelProviderConfig::getApiKeyCiphertext)
                .orElseGet(() -> {
                    if ("deepseek".equals(fallback.provider())) {
                        return secretCryptoService.encrypt(deepSeekApiKey);
                    }
                    if ("aliyun".equals(fallback.provider())) {
                        return secretCryptoService.encrypt(embeddingApiKey);
                    }
                    return null;
                });
    }

    private String requireNonBlank(String candidate, String fallback, String message) {
        String value = candidate != null && !candidate.isBlank() ? candidate.trim() : fallback;
        if (value == null || value.isBlank()) {
            throw new CustomException(message, HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    public record ModelProviderSettingsView(
            ScopeSettingsView llm,
            ScopeSettingsView embedding
    ) {
    }

    public record ScopeSettingsView(
            String scope,
            String activeProvider,
            List<ProviderConfigView> providers
    ) {
    }

    public record ProviderConfigView(
            String provider,
            String displayName,
            String apiStyle,
            String apiBaseUrl,
            String model,
            Integer dimension,
            boolean enabled,
            boolean active,
            boolean hasApiKey,
            String maskedApiKey
    ) {
    }

    public record ProviderUpsertRequest(
            String provider,
            String apiBaseUrl,
            String model,
            String apiKey,
            Integer dimension,
            Boolean enabled
    ) {
    }

    public record UpdateScopeRequest(
            String activeProvider,
            List<ProviderUpsertRequest> providers
    ) {
    }

    public record ProviderConnectionTestRequest(
            String provider,
            String apiBaseUrl,
            String model,
            String apiKey,
            Integer dimension
    ) {
    }

    public record ConnectivityTestView(
            boolean success,
            String message,
            long latencyMs
    ) {
    }

    public record ActiveProviderView(
            String provider,
            String displayName,
            String apiStyle,
            String apiBaseUrl,
            String model,
            String apiKey,
            Integer dimension
    ) {
    }
}
