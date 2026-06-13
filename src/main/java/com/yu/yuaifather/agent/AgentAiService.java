package com.yu.yuaifather.agent;

import dev.langchain4j.service.SystemMessage;

/**
 * 多 Agent 流水线的 AI 服务接口。
 * <p>
 * 各 Agent 通过本接口的对应方法触发对应 prompt 的 LLM 调用。
 * 输出统一为字符串（多为严格 JSON），由各 Agent 自行解析为 DTO。
 */
public interface AgentAiService {

    /**
     * ProductAgent：将用户需求转换为 RequirementDoc JSON。
     */
    @SystemMessage(fromResource = "prompt/agent-product-system-prompt.txt")
    String generateRequirementDoc(String userPrompt);

    /**
     * ArchitectAgent：基于 requirementDoc 输出 GenerationPlan JSON。
     * 输入由 Orchestrator 拼装为 JSON 字符串。
     */
    @SystemMessage(fromResource = "prompt/agent-architect-system-prompt.txt")
    String generatePlan(String architectInput);

    /**
     * StyleAgent：基于 plan.colorTheme 输出包含 variables.css / global.css 两段的 JSON。
     * 期望输出：{ "variables_css": "...", "global_css": "..." }
     */
    @SystemMessage(fromResource = "prompt/agent-style-system-prompt.txt")
    String generateStyleSheets(String styleInput);

    /**
     * CriticAgent：基于检查输入输出 CriticReport JSON。
     */
    @SystemMessage(fromResource = "prompt/agent-critic-system-prompt.txt")
    String review(String reviewInput);
}
