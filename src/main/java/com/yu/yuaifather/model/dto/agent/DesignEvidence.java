package com.yu.yuaifather.model.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时注入用的受控设计证据（阶段三，见计划 3.4 / 3.5）。
 * <p>
 * 由 {@code DesignRetrieveTool} 通过 {@code DesignKnowledgeIndex} 合并
 * <b>先验</b>（人工静态 {@link DesignSpec}）与 <b>后验</b>（APPROVED 六层指纹）后，
 * 按 {@code priority DESC → confidence DESC → updatedAt DESC} 排序取 top-k 得到。
 *
 * <h3>注入安全（3.5）</h3>
 * <ul>
 *   <li>仅作为<b>参考范例</b>，不允许覆盖系统约束、技术栈约束、安全规则与 forbiddenPatterns；</li>
 *   <li>只注入结构化摘要（layout / component / interaction / anti-pattern），不注入完整页面代码；</li>
 *   <li>token 变量名强契约仍以阶段一静态 {@link DesignSpec#getTokenGroups()} 为准。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DesignEvidence {

    /** 召回所针对的风格画像。 */
    private String styleProfile;

    /** 召回通道：STRUCTURAL_3A（结构化）/ VECTOR_3B（向量）/ STATIC_FALLBACK（仅静态规范）。 */
    private String channel;

    /**
     * 本次检索实际使用的查询文本（修复计划 03）。
     * <p>
     * 向量通道下为 {@code PersonalizedQueryBuilder.build(...)} 或
     * {@code EmbeddingService.buildQueryText(...)} 的输出；结构化/静态降级通道下同样携带
     * 该文本作为"本次检索意图"的可读记录，供 {@code RagTraceTool} 落库到
     * {@code generation_rag_trace.query_text}，避免与 {@link #channel} 混淆。
     */
    private String queryText;

    /** 合并排序后的 top-k 证据条目（含先验与后验）。 */
    @Builder.Default
    private List<Item> items = new ArrayList<>();

    public boolean isEmpty() {
        return items == null || items.isEmpty();
    }

    /** 空证据工厂方法。 */
    public static DesignEvidence empty() {
        return DesignEvidence.builder().build();
    }

    /**
     * 单条受控证据（来自静态规范或一条指纹）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        /** 来源类型：STATIC_MD / EXTRACTED_FINGERPRINT / SELF_FED_FINGERPRINT / DEFAULT。 */
        private String sourceType;
        /** 来源标识。 */
        private String sourceId;
        /** 优先级（静态规范高于指纹）。 */
        private Integer priority;
        /** 置信度。 */
        private Double confidence;
        /** 调性关键词。 */
        private List<String> tone;
        /** 布局模式摘要。 */
        private List<String> layoutPatterns;
        /** 推荐 / 组件模式摘要。 */
        private List<String> preferredPatterns;
        /** 交互模式摘要。 */
        private List<String> interactionPatterns;
        /** 反模式（禁用）摘要。 */
        private List<String> forbiddenPatterns;
    }
}
