package com.yu.yuaifather.service.design;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yu.yuaifather.agent.service.AgentAiService;
import com.yu.yuaifather.utils.JsonExtractor;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * DesignExtractorAgent：六层指纹提炼器（阶段二「后验证据」，<b>离线</b>）。
 * <p>
 * 调用 LLM，把精选页面摘要 / 截图描述按六层契约提炼为 {@link DesignFingerprintDTO}。
 *
 * <h3>定位</h3>
 * <ul>
 *   <li>属于<b>离线预热</b>链路，<b>不</b>接入 {@code GenerationOrchestrator} 实时生成流水线，
 *       失败不影响实时生成（见三阶段对照表）；</li>
 *   <li>输入必须先经 {@link DesignSourcePreprocessor} 归一化（剥离脚本与业务文案），
 *       不直接把原始 HTML 喂给 LLM（呼应 2.6 prompt injection 隔离）；</li>
 *   <li>输出经 {@link FingerprintParser} 解析 + schema 校验 + 置信度计算，再交由
 *       {@code DesignFingerprintService} 入库为 CANDIDATE。</li>
 * </ul>
 *
 * <p>与实时 Worker 不同，这里不实现 {@code Agent#handle} 的 Flux 事件流，
 * 而是提供同步 {@link #extract} 方法供离线治理流程调用。
 */
@Slf4j
@Component
public class DesignExtractorAgent {

    public static final String AGENT_NAME = "DesignExtractorAgent";

    private final AgentAiService agentAiService;
    private final DesignSourcePreprocessor preprocessor;
    private final FingerprintParser fingerprintParser;

    @Autowired
    public DesignExtractorAgent(AgentAiService agentAiService,
                                DesignSourcePreprocessor preprocessor,
                                FingerprintParser fingerprintParser) {
        this.agentAiService = agentAiService;
        this.preprocessor = preprocessor;
        this.fingerprintParser = fingerprintParser;
    }

    /**
     * 从原始 HTML 提炼六层指纹。
     *
     * @param rawHtml         已获取的页面 HTML（抓取需另行遵守 SSRF 规则）
     * @param styleProfileHint 风格提示（可空，仅作 LLM 参考，最终以输出为准）
     * @param sourceId        来源标识（页面 ID / 批次号）
     * @return 解析后的指纹 DTO；提炼或解析失败返回 {@code null}（离线链路不抛出中断）
     */
    public DesignFingerprintDTO extractFromHtml(String rawHtml, String styleProfileHint, String sourceId) {
        String summary = preprocessor.summarizeHtml(rawHtml);
        if (StrUtil.isBlank(summary)) {
            log.warn("DesignExtractorAgent 页面摘要为空，跳过 sourceId={}", sourceId);
            return null;
        }
        return extract(summary, styleProfileHint, sourceId);
    }

    /**
     * 从已归一化的结构化页面摘要提炼六层指纹。
     */
    public DesignFingerprintDTO extract(String pageSummary, String styleProfileHint, String sourceId) {
        if (StrUtil.isBlank(pageSummary)) {
            return null;
        }
        try {
            ObjectNode input = JsonExtractor.mapper().createObjectNode();
            input.put("pageSummary", pageSummary);
            if (StrUtil.isNotBlank(styleProfileHint)) {
                input.put("styleProfileHint", styleProfileHint);
            }
            if (StrUtil.isNotBlank(sourceId)) {
                input.put("sourceId", sourceId);
            }
            String raw = agentAiService.generateFingerprint(input.toString());
            DesignFingerprintDTO dto = fingerprintParser.parse(raw);
            if (StrUtil.isNotBlank(sourceId)) {
                dto.setSourceId(sourceId);
            }
            log.info("DesignExtractorAgent 提炼完成 sourceId={} profile={}", sourceId, dto.getStyleProfile());
            return dto;
        } catch (Exception e) {
            log.error("DesignExtractorAgent 提炼失败 sourceId={} msg={}", sourceId, e.getMessage());
            return null;
        }
    }
}
