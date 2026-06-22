package com.yu.yuaifather.ai.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 压缩后的 Prompt 上下文，供 LLM Agent 消费。
 * <p>
 * 由 ContextCompressTool 根据目标 Agent 类型生成定制化内容，
 * 每个 Agent 消费自己的 PromptContext 而非完整的 DesignContext。
 */
@Data
@Builder
public class PromptContext {

    /** 风格标识。 */
    private String styleProfile;

    /** 设计规范摘要（压缩后）。 */
    private String designSpecSummary;

    /** 用户偏好摘要（压缩后）。 */
    private String userPreferenceSummary;

    /** 项目品牌摘要（压缩后）。 */
    private String projectBrandSummary;

    /** RAG 证据摘要（压缩后）。 */
    private String evidenceSummary;

    /** 图片映射（压缩后仅保留 key→url 对）。 */
    private Map<String, String> imageMap;

    /** 目标 Agent 名称（PageGenerateAgent / CoderAgent / StyleAgent / CriticAgent）。 */
    private String targetAgent;

    /** 最终拼接好的完整 Prompt 文本。 */
    private String fullPrompt;
}
