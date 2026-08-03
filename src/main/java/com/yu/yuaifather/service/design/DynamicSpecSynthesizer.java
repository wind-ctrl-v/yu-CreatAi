package com.yu.yuaifather.service.design;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yu.yuaifather.agent.service.AgentAiService;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
import com.yu.yuaifather.model.dto.agent.DesignSpec;
import com.yu.yuaifather.model.dto.agent.GenerationPlan;
import com.yu.yuaifather.model.dto.agent.RequirementDoc;
import com.yu.yuaifather.utils.JsonExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DynamicSpecSynthesizer：动态设计规范合成器（覆盖度优化计划 阶段二，第四档降级）。
 * <p>
 * 当三档检索（VECTOR_3B / STRUCTURAL_3A / 静态规范）均未命中指纹证据、
 * 且同一 styleProfile 的 miss 次数达到阈值时，调用 LLM 根据需求描述
 * 现场合成一份六层设计规范（{@link DesignFingerprintDTO} 契约）。
 *
 * <h3>产物两级去向</h3>
 * <ol>
 *   <li><b>本次生成</b>：投影为 {@link DesignSpec} 注入 DesignEvidence（仅作参考证据，
 *       不覆盖 Blackboard 中的静态 designSpec，tokenGroups 强契约仍以静态规范为准）；</li>
 *   <li><b>入库候选</b>：由调用方经 {@code DesignFingerprintService#saveSynthesizedCandidate}
 *       落为 CANDIDATE（signature 去重），人工审核 APPROVED 后才进入向量召回池。</li>
 * </ol>
 *
 * <h3>关键约束</h3>
 * <ul>
 *   <li>confidence 固定压低（{@value #SYNTHESIZED_CONFIDENCE}）：合成无页面证据支撑，
 *       避免在 confidence 排序中排到真实提炼指纹之前；</li>
 *   <li>priority 固定（{@value #SYNTHESIZED_PRIORITY}）：低于人工静态基线(50)与真实指纹(30)；</li>
 *   <li>styleProfile 允许自由标签：合成产物不受白名单限制，用于承载白名单外的需求风格。</li>
 * </ul>
 */
@Slf4j
@Component
public class DynamicSpecSynthesizer {

    /** 合成规范的来源类型。 */
    public static final String SOURCE_TYPE = "DYNAMIC_SYNTHESIS";
    /** 合成规范固定置信度（无证据支撑，人为压低）。 */
    public static final double SYNTHESIZED_CONFIDENCE = 0.35;
    /** 合成规范固定优先级（低于人工静态基线 50 与真实指纹 30）。 */
    public static final int SYNTHESIZED_PRIORITY = 25;

    private final AgentAiService agentAiService;
    private final FingerprintParser fingerprintParser;
    private final FingerprintProjector fingerprintProjector;

    @Autowired
    public DynamicSpecSynthesizer(AgentAiService agentAiService,
                                  FingerprintParser fingerprintParser,
                                  FingerprintProjector fingerprintProjector) {
        this.agentAiService = agentAiService;
        this.fingerprintParser = fingerprintParser;
        this.fingerprintProjector = fingerprintProjector;
    }

    /**
     * 根据需求上下文动态合成六层设计规范。
     *
     * @param styleProfile 归一化后的风格画像（可能为 default）
     * @param queryTags    需求关键词
     * @param requirement  需求文档（可空）
     * @param plan         生成计划（可空）
     * @return 合成规范的 DesignSpec 投影；合成或解析失败返回 {@code null}（调用方降级继续，不阻断生成）
     */
    public DesignSpec synthesize(String styleProfile, List<String> queryTags,
                                 RequirementDoc requirement, GenerationPlan plan) {
        DesignFingerprintDTO dto = synthesizeDto(styleProfile, queryTags, requirement, plan);
        if (dto == null) {
            return null;
        }
        return fingerprintProjector.project(dto, dto.getSourceId(),
                SOURCE_TYPE, SYNTHESIZED_PRIORITY, SYNTHESIZED_CONFIDENCE);
    }

    /**
     * 合成原始六层指纹 DTO（入库候选用）。
     *
     * @return 解析后的 DTO；失败返回 {@code null}
     */
    public DesignFingerprintDTO synthesizeDto(String styleProfile, List<String> queryTags,
                                              RequirementDoc requirement, GenerationPlan plan) {
        try {
            String raw = agentAiService.synthesizeDesignSpec(buildInput(styleProfile, queryTags, requirement, plan));
            DesignFingerprintDTO dto = fingerprintParser.parse(raw);
            dto.setSourceType(SOURCE_TYPE);
            dto.setSourceId("dynamic:" + StrUtil.blankToDefault(dto.getStyleProfile(), "unknown"));
            log.info("动态设计规范合成完成 profile={} tone={}", dto.getStyleProfile(), dto.getTone());
            return dto;
        } catch (Exception e) {
            log.warn("动态设计规范合成失败，返回 null 降级 profile={} msg={}", styleProfile, e.getMessage());
            return null;
        }
    }

    /**
     * 拼装合成输入（仅需求摘要级信息，控制 token 消耗）。
     */
    private String buildInput(String styleProfile, List<String> queryTags,
                              RequirementDoc requirement, GenerationPlan plan) {
        ObjectNode input = JsonExtractor.mapper().createObjectNode();
        input.put("styleProfile", StrUtil.blankToDefault(styleProfile, "default"));
        if (queryTags != null && !queryTags.isEmpty()) {
            input.put("queryTags", String.join(", ", queryTags.stream().limit(12).toList()));
        }
        if (requirement != null) {
            if (StrUtil.isNotBlank(requirement.getSummary())) {
                input.put("requirementSummary", requirement.getSummary());
            }
            if (StrUtil.isNotBlank(requirement.getTargetUser())) {
                input.put("targetUser", requirement.getTargetUser());
            }
            if (requirement.getCoreFeatures() != null && !requirement.getCoreFeatures().isEmpty()) {
                input.put("coreFeatures", String.join(", ", requirement.getCoreFeatures().stream().limit(6).toList()));
            }
        }
        if (plan != null && StrUtil.isNotBlank(plan.getRequirementSummary())) {
            input.put("planSummary", plan.getRequirementSummary());
        }
        return input.toString();
    }
}
