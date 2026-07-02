package com.yu.yuaifather.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * EmbeddingModel 统一配置。
 * 默认使用 SHA-256 哈希兜底（1536维），设置 {@code agent.embedding.use-openai=true} + OPENAI_API_KEY 切换 text-embedding-3-small。
 */
@Slf4j
@Configuration
public class EmbeddingModelConfig {

    private static final int DIMENSION = 1536;

    @Value("${agent.embedding.use-openai:false}")
    private boolean useOpenAI;

    @Value("${langchain4j.open-ai.embedding-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.embedding-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.embedding-model.model-name}")
    private String modelName;

    @Bean
    public EmbeddingModel embeddingModel() {
        if (useOpenAI) {
            log.info("使用语义Embedding模型 {} ({})", modelName, baseUrl);
            return OpenAiEmbeddingModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .logRequests(true)
                    .logResponses(true)
                    .build();
        }
        log.info("使用哈希兜底 EmbeddingModel（1536维）");
        return new HashEmbeddingModel(DIMENSION);
    }

    /** 基于 SHA-256 的确定性向量生成 */
    private static class HashEmbeddingModel implements EmbeddingModel {
        private final int dim;
        HashEmbeddingModel(int dim) { this.dim = dim; }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segs) {
            return Response.from(segs.stream().map(s -> embed(s.text()).content()).toList());
        }
        @Override public Response<Embedding> embed(TextSegment s) { return embed(s.text()); }

        @Override
        public Response<Embedding> embed(String text) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] h = md.digest(text.getBytes(StandardCharsets.UTF_8));
                float[] v = new float[dim];
                for (int i = 0; i < dim; i++) {
                    int sign = ((h[i % h.length] & 0xFF) % 2 == 0) ? 1 : -1;
                    float val = ((h[(i / h.length + i) % h.length] & 0xFF) / 128.0f - 1.0f) * sign;
                    v[i] = (float) (Math.round(val * 1000.0) / 1000.0);
                }
                return Response.from(new Embedding(v));
            } catch (Exception e) { return Response.from(new Embedding(new float[dim])); }
        }
        @Override public int dimension() { return dim; }
    }
}
