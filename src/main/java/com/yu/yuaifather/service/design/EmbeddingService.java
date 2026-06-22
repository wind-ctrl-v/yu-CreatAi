package com.yu.yuaifather.service.design;

import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Embedding 服务：语义文本构造 + LangChain4j 向量生成。
 * <p>
 * 职责：
 * <ol>
 *   <li>从 {@link DesignFingerprintDTO} 构造语义摘要文本（embedding 模型输入）；</li>
 *   <li>调用 LangChain4j {@link EmbeddingModel} 将文本转为向量；</li>
 *   <li>检索端构造查询语义文本。</li>
 * </ol>
 *
 * @author yu
 * @see dev.langchain4j.model.embedding.EmbeddingModel
 */
@Service
@ConditionalOnBean(EmbeddingModel.class)
@Slf4j
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 从六层指纹 DTO 构造语义摘要文本。
     */
    public String buildSemanticText(DesignFingerprintDTO dto) {
        if (dto == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendTag(sb, "设计风格", dto.getStyleProfile());

        if (dto.getTokens() != null) {
            DesignFingerprintDTO.Tokens t = dto.getTokens();
            sb.append("[设计Token: ");
            if (t.getColorRatio() != null) sb.append("配色=").append(t.getColorRatio()).append(", ");
            if (t.getFontScale() != null) sb.append("字号阶梯=").append(String.join("/", t.getFontScale())).append(", ");
            if (t.getRadii() != null) sb.append("圆角=").append(t.getRadii()).append(", ");
            if (t.getShadow() != null) sb.append("阴影=").append(t.getShadow());
            sb.append("] ");
        }

        if (dto.getLayout() != null) {
            DesignFingerprintDTO.Layout l = dto.getLayout();
            sb.append("[布局: ");
            if (l.getSkeleton() != null) sb.append("骨架=").append(l.getSkeleton()).append(", ");
            if (l.getWhitespace() != null) sb.append("留白=").append(l.getWhitespace()).append(", ");
            if (l.getFocus() != null) sb.append("视觉重心=").append(l.getFocus());
            sb.append("] ");
        }

        if (dto.getComponents() != null && !dto.getComponents().isEmpty()) {
            sb.append("[组件: ").append(String.join(", ", dto.getComponents())).append("] ");
        }

        if (dto.getInteraction() != null) {
            DesignFingerprintDTO.Interaction i = dto.getInteraction();
            sb.append("[交互: ");
            if (i.getMotion() != null) sb.append("动效=").append(i.getMotion()).append(", ");
            if (i.getTransition() != null) sb.append("过渡=").append(i.getTransition());
            sb.append("] ");
        }

        if (dto.getAntiPatterns() != null && !dto.getAntiPatterns().isEmpty()) {
            sb.append("[避免: ").append(String.join(", ", dto.getAntiPatterns())).append("] ");
        }

        if (dto.getTone() != null && !dto.getTone().isEmpty()) {
            sb.append("[调性: ").append(String.join(", ", dto.getTone())).append("]");
        }

        return sb.toString().trim();
    }

    /**
     * 检索端构造查询语义文本。
     */
    public String buildQueryText(String styleProfile, List<String> queryTags) {
        StringBuilder sb = new StringBuilder();
        if (styleProfile != null && !styleProfile.isBlank()) {
            appendTag(sb, "设计风格", styleProfile);
        }
        if (queryTags != null && !queryTags.isEmpty()) {
            sb.append("[需求关键词: ").append(String.join(", ", queryTags)).append("]");
        }
        return sb.toString().trim();
    }

    /**
     * 将文本转为向量。
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        try {
            Embedding embedding = embeddingModel.embed(text).content();
            return embedding.vector();
        } catch (Exception e) {
            log.error("Embedding 调用失败 text_head={}", text.substring(0, Math.min(80, text.length())), e);
            throw new RuntimeException("Embedding 调用失败: " + e.getMessage(), e);
        }
    }

    private void appendTag(StringBuilder sb, String tag, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("[").append(tag).append(": ").append(value).append("] ");
        }
    }
}
