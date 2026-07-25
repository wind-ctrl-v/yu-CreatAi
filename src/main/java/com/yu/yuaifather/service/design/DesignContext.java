package com.yu.yuaifather.service.design;

import com.yu.yuaifather.model.dto.agent.*;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 统一设计上下文 —— DesignContextAssembler 的输出。
 * <p>
 * 包含生成所需的全部上下文信息，供 PageGenerateAgent / CoderAgent / StyleAgent 消费。
 * Agent 只需读取 {@link #promptContext} 即可获得定制化的 Prompt 文本，
 * 不再需要自行查询用户偏好或 RAG 证据。
 * <p>
 * ★ 包结构说明（包结构优化计划问题 3）：本类原位于 {@code orchestrator} 包，
 * 现迁移到 {@code service.design}（与 {@code DesignKnowledgeIndex}/{@code FingerprintProjector}
 * 等同域数据组装类聚合），编排器包只保留真正的编排逻辑类 {@code GenerationOrchestrator}。
 */
@Data
@Builder
public class DesignContext {

    /** 静态设计规范（由 DesignTokenTool 产出）。 */
    private DesignSpec designSpec;

    /** 用户偏好上下文（由 UserPreferenceTool 产出）。 */
    private UserDesignContext userContext;

    /** 项目品牌上下文（由 ProjectProfileTool 产出）。 */
    private ProjectBrandContext projectContext;

    /** RAG 召回的设计证据（由 DesignRetrieveTool 产出，已 rerank）。 */
    private DesignEvidence designEvidence;

    /** 【已废弃】图片改由生成后的占位符回填处理，此字段不再被写入，保留仅为兼容。 */
    private Map<String, String> imageMap;

    /** 压缩后的 Prompt 上下文（由 ContextCompressTool 产出）。 */
    private PromptContext promptContext;

    /** 原始检索结果（用于 trace 记录）。 */
    private DesignEvidence rawRetrieveResult;

    /** 命中指纹总数（用于 trace）。 */
    private int fingerprintHitCount;
}
