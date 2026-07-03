package com.yu.yuaifather.service.design;

import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.model.dto.agent.*;
import com.yu.yuaifather.tool.core.ToolContext;
import com.yu.yuaifather.tool.system.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DesignContextAssembler：固定资产上下文装配器。
 * <p>
 * 串联 7 个 System Tool，输出统一的 DesignContext，供 PageGenerateAgent / CoderAgent / StyleAgent 消费。
 * Agent 只需消费 PromptContext 中的 fullPrompt，不再独自查询用户偏好或 RAG 证据。
 * <p>
 * ★ 包结构说明：本类原位于 {@code orchestrator} 包，现迁移到 {@code service.design}
 * （见包结构优化计划问题 3），职责上更贴近"设计上下文数据组装服务"而非编排器本身。
 *
 * <h3>编排顺序</h3>
 * <pre>
 * DesignTokenTool → UserPreferenceTool → ProjectProfileTool
 * → DesignRetrieveTool → ImageSearchTool → ContextCompressTool
 * </pre>
 */
@Slf4j
@Component
public class DesignContextAssembler {

    private final DesignTokenTool designTokenTool;
    private final UserPreferenceTool userPreferenceTool;
    private final ProjectProfileTool projectProfileTool;
    private final DesignRetrieveTool designRetrieveTool;
    private final ImageSearchTool imageSearchTool;
    private final ContextCompressTool contextCompressTool;

    @Autowired
    public DesignContextAssembler(DesignTokenTool designTokenTool,
                                   UserPreferenceTool userPreferenceTool,
                                   ProjectProfileTool projectProfileTool,
                                   @Autowired(required = false) DesignRetrieveTool designRetrieveTool,
                                   ImageSearchTool imageSearchTool,
                                   ContextCompressTool contextCompressTool) {
        this.designTokenTool = designTokenTool;
        this.userPreferenceTool = userPreferenceTool;
        this.projectProfileTool = projectProfileTool;
        this.designRetrieveTool = designRetrieveTool;
        this.imageSearchTool = imageSearchTool;
        this.contextCompressTool = contextCompressTool;
    }

    /**
     * 装配完整的 DesignContext。
     *
     * @param input       包含 userId、projectId、requirementDoc、generationPlan 等输入
     * @param toolContext  系统注入的工具调用上下文（含权限）
     * @param targetAgent 目标 Agent 标签（用于 Prompt 裁剪）
     * @return 完整的 DesignContext
     */
    public DesignContext assemble(DesignContextInput input, ToolContext toolContext, String targetAgent) {
        DesignContext.DesignContextBuilder builder = DesignContext.builder();
        Long userId = toolContext.getCurrentUserId();
        Long projectId = toolContext.getCurrentProjectId();
        RequirementDoc requirement = input.getRequirementDoc();
        GenerationPlan plan = input.getGenerationPlan();

        // 1. 加载静态设计规范
        String styleProfile = resolveProfile(plan);
        DesignSpec designSpec = designTokenTool.execute(styleProfile, toolContext);
        builder.designSpec(designSpec);

        // 2. 读取用户偏好
        UserDesignContext userContext = UserDesignContext.empty();
        if (userId != null) {
            try {
                userContext = userPreferenceTool.execute(userId, toolContext);
            } catch (Exception e) {
                log.warn("UserPreferenceTool 失败，降级为空上下文 userId={}", userId, e);
            }
        }
        builder.userContext(userContext);

        // 3. 读取项目品牌
        ProjectBrandContext projectContext = ProjectBrandContext.empty();
        if (projectId != null) {
            try {
                projectContext = projectProfileTool.execute(projectId, toolContext);
            } catch (Exception e) {
                log.warn("ProjectProfileTool 失败，降级为空上下文 projectId={}", projectId, e);
            }
        }
        builder.projectContext(projectContext);

        // 4. 向量检索 + 权限过滤
        DesignEvidence rawEvidence = DesignEvidence.empty();
        if (designRetrieveTool != null) {
            try {
                List<String> queryTags = buildQueryTags(plan, designSpec);
                DesignRetrieveTool.RetrieveInput retrieveInput = new DesignRetrieveTool.RetrieveInput(
                        styleProfile, queryTags, designSpec, requirement, plan, userContext, 3);
                rawEvidence = designRetrieveTool.execute(retrieveInput, toolContext);
            } catch (Exception e) {
                log.warn("DesignRetrieveTool 失败，降级为空证据", e);
            }
        }
        builder.rawRetrieveResult(rawEvidence);
        DesignEvidence evidence = rawEvidence != null ? rawEvidence : DesignEvidence.empty();
        builder.designEvidence(evidence);
        builder.fingerprintHitCount(evidence.getItems() != null ? evidence.getItems().size() : 0);

        // 5. 检索图片
        Map<String, String> imageMap = Map.of();
        if (plan != null && plan.getImageSlots() != null && !plan.getImageSlots().isEmpty()) {
            try {
                imageMap = imageSearchTool.execute(plan.getImageSlots(), toolContext);
            } catch (Exception e) {
                log.warn("ImageSearchTool 失败，降级为空映射", e);
            }
        }
        builder.imageMap(imageMap);

        // 6. 压缩上下文生成 PromptContext
        PromptContext promptContext = contextCompressTool.execute(
                new ContextCompressTool.CompressInput(
                        designSpec, userContext,
                        projectContext,
                        evidence, imageMap, targetAgent),
                toolContext);
        builder.promptContext(promptContext);

        return builder.build();
    }

    /** 提取 styleProfile（优先级：plan > staticSpec > "default"）。 */
    private String resolveProfile(GenerationPlan plan) {
        if (plan != null && StrUtil.isNotBlank(plan.getStyleProfile())) {
            return plan.getStyleProfile();
        }
        return "default";
    }

    /** 构造软匹配标签。 */
    private List<String> buildQueryTags(GenerationPlan plan, DesignSpec staticSpec) {
        Set<String> tags = new LinkedHashSet<>();
        if (staticSpec != null && staticSpec.getTone() != null) {
            tags.addAll(staticSpec.getTone());
        }
        if (plan != null && StrUtil.isNotBlank(plan.getRequirementSummary())) {
            for (String w : plan.getRequirementSummary().split("[\\s,，。、/]+")) {
                if (w.length() >= 2) {
                    tags.add(w);
                }
            }
        }
        return new ArrayList<>(tags);
    }
}
