package com.yu.yuaifather.agent.workers;

import cn.hutool.json.JSONUtil;
import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.support.ImagePlaceholderReplacer;
import com.yu.yuaifather.ai.AiCodeGeneratorService;
import com.yu.yuaifather.ai.AiCodeGeneratorServiceFactory;
import com.yu.yuaifather.ai.enums.CodeGenTypeEnum;
import com.yu.yuaifather.ai.model.dto.CriticReport;
import com.yu.yuaifather.ai.model.dto.DesignEvidence;
import com.yu.yuaifather.ai.model.dto.DesignSpec;
import com.yu.yuaifather.ai.model.dto.GenerationPlan;
import com.yu.yuaifather.ai.model.message.AiResponseMessage;
import com.yu.yuaifather.ai.model.message.ToolExecutedMessage;
import com.yu.yuaifather.ai.model.message.ToolRequestMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CoderAgent：复用现有 Vue 项目流式生成（带 File 工具）按 plan 把 fileStructure 落盘。
 * <p>
 * 流式完成后触发图片占位符替换。
 */
@Slf4j
@Component
public class CoderAgent implements Agent {

    public static final String AGENT_NAME = "CoderAgent";

    private final AiCodeGeneratorServiceFactory factory;
    private final ImagePlaceholderReplacer placeholderReplacer;

    @Autowired
    public CoderAgent(AiCodeGeneratorServiceFactory factory,
                      ImagePlaceholderReplacer placeholderReplacer) {
        this.factory = factory;
        this.placeholderReplacer = placeholderReplacer;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.create(sink -> {
            sink.next(AgentEvent.status(AGENT_NAME, "开始生成代码"));
            GenerationPlan plan = ctx.getBlackboard().getPlan();
            String userMessage = buildUserMessage(ctx, plan);
            AiCodeGeneratorService service = factory.getAiCodeGeneratorService(
                    ctx.getAppId(), CodeGenTypeEnum.VUE_PROJECT);
            TokenStream stream = service.generateVueProjectCodeStream(ctx.getAppId(), userMessage);
            stream.onPartialResponse(partial -> sink.next(AgentEvent.builder()
                            .type(AgentEvent.EventType.TEXT_CHUNK)
                            .agentName(AGENT_NAME)
                            .content(JSONUtil.toJsonStr(new AiResponseMessage(partial)))
                            .build()))
                    .onPartialToolExecutionRequest((idx, req) -> sink.next(AgentEvent.builder()
                            .type(AgentEvent.EventType.TOOL_CALL)
                            .agentName(AGENT_NAME)
                            .content(JSONUtil.toJsonStr(new ToolRequestMessage(req)))
                            .data(req)
                            .build()))
                    .onToolExecuted((ToolExecution exec) -> {
                        try {
                            String args = exec.request() == null ? "" : exec.request().arguments();
                            if (args != null && args.contains("relativeFilePath")) {
                                String filePath = JSONUtil.parseObj(args).getStr("relativeFilePath");
                                ctx.getBlackboard().addGeneratedFile(filePath);
                            }
                        } catch (Exception ignore) {
                            // 解析失败不影响主流程
                        }
                        sink.next(AgentEvent.builder()
                                .type(AgentEvent.EventType.TOOL_RESULT)
                                .agentName(AGENT_NAME)
                                .content(JSONUtil.toJsonStr(new ToolExecutedMessage(exec)))
                                .build());
                    })
                    .onCompleteResponse(resp -> {
                        try {
                            placeholderReplacer.replaceAll(ctx.getAppId());
                        } catch (Exception e) {
                            log.warn("图片占位符替换失败 appId={} msg={}", ctx.getAppId(), e.getMessage());
                        }
                        sink.next(AgentEvent.status(AGENT_NAME, "代码生成完成"));
                        sink.complete();
                    })
                    .onError(err -> {
                        log.error("CoderAgent 流式生成失败 appId={}", ctx.getAppId(), err);
                        sink.next(AgentEvent.error(AGENT_NAME, "代码生成失败: " + err.getMessage()));
                        sink.error(err);
                    })
                    .start();
        });
    }

    /**
     * 全局样式文件：由 StyleAgent 独占维护，CoderAgent 不得写入其内容。
     */
    private static final List<String> STYLE_OWNED_FILES = List.of(
            "src/styles/variables.css", "src/styles/global.css");

    /**
     * 拼装传给 LLM 的用户消息：原始 prompt + 过滤后的 plan 摘要 + 设计强约束 + Token 清单 + 上一轮质检问题。
     * <p>
     * 关键处理（见计划 1.5）：
     * <ul>
     *   <li>过滤 plan 副本，移除 StyleAgent 独占的全局样式文件，避免与「禁止写样式文件」指令冲突；</li>
     *   <li>显式告知必须在入口文件 import 全局样式（呼应 #2）；</li>
     *   <li>注入 tokenGroups 确切变量名清单，只能引用、不得自创（呼应 #1）；</li>
     *   <li>注入 forbidden/preferred 强约束；</li>
     *   <li>若上一轮质检未通过，注入 issues 清单，根除盲目重试（呼应 1.5.3）。</li>
     * </ul>
     */
    private String buildUserMessage(AgentContext ctx, GenerationPlan plan) {
        String userPrompt = ctx.getUserPrompt();
        DesignSpec designSpec = ctx.getBlackboard().getDesignSpec();
        CriticReport criticReport = ctx.getBlackboard().getCriticReport();

        StringBuilder sb = new StringBuilder();
        sb.append("原始需求：").append(userPrompt).append("\n\n");

        if (plan != null) {
            sb.append("请严格按以下生成计划落盘代码：\n");
            sb.append(JSONUtil.toJsonStr(filterStyleFiles(plan)));
            sb.append("\n\n务必使用【文件写入工具】逐文件创建，图片资源全部使用 {{IMG:英文关键词}} 占位符。\n\n");
        }

        // 全局样式文件规则（不写内容 + 必须引用）
        sb.append("## 全局样式文件规则（必须遵守）\n");
        sb.append("- 禁止创建或覆盖 src/styles/variables.css 与 src/styles/global.css；这两个文件由系统（StyleAgent）独占维护并已自动生成。\n");
        sb.append("- 你【必须】在入口文件 src/main.js 中写入 `import './styles/variables.css'` 与 `import './styles/global.css'`，否则全局样式不会生效。\n");
        sb.append("- 组件内样式只能引用下方 Token 清单中给出的确切变量名（如 var(--color-primary-500)），禁止自创未在清单中出现的变量名。\n\n");

        // Token 变量名强契约清单
        appendTokenContract(sb, designSpec);

        // 设计强约束（forbidden / preferred）
        appendDesignConstraints(sb, designSpec);

        // 运行时召回的受控设计证据（阶段三，优先消费；缺省时仅用上面的静态规范）
        appendEvidence(sb, ctx.getBlackboard().getDesignEvidence());

        // 上一轮质检未通过的问题清单
        appendCriticIssues(sb, criticReport);

        return sb.toString();
    }

    /**
     * 返回过滤掉全局样式文件的 plan 副本，不修改 Blackboard 中的原 plan。
     */
    private GenerationPlan filterStyleFiles(GenerationPlan plan) {
        if (plan.getFileStructure() == null) {
            return plan;
        }
        List<String> filtered = new ArrayList<>();
        for (String f : plan.getFileStructure()) {
            if (f != null && !STYLE_OWNED_FILES.contains(f.trim())) {
                filtered.add(f);
            }
        }
        return GenerationPlan.builder()
                .techStack(plan.getTechStack())
                .requirementSummary(plan.getRequirementSummary())
                .styleProfile(plan.getStyleProfile())
                .styleProfileConfidence(plan.getStyleProfileConfidence())
                .fileStructure(filtered)
                .routerConfig(plan.getRouterConfig())
                .imageSlots(plan.getImageSlots())
                .colorTheme(plan.getColorTheme())
                .build();
    }

    /**
     * 注入 tokenGroups 的确切变量名清单（只能引用，不得自创）。
     */
    private void appendTokenContract(StringBuilder sb, DesignSpec designSpec) {
        if (designSpec == null || designSpec.getTokenGroups() == null || designSpec.getTokenGroups().isEmpty()) {
            return;
        }
        sb.append("## 可用设计 Token 变量名清单（组件样式只能引用以下变量名，不得自创）\n");
        for (Map.Entry<String, Map<String, String>> group : designSpec.getTokenGroups().entrySet()) {
            Map<String, String> vars = group.getValue();
            if (vars == null || vars.isEmpty()) {
                continue;
            }
            sb.append("- ").append(group.getKey()).append("：")
                    .append(String.join(", ", vars.keySet())).append("\n");
        }
        sb.append("\n");
    }

    /**
     * 注入设计禁用 / 推荐清单作为强约束。
     */
    private void appendDesignConstraints(StringBuilder sb, DesignSpec designSpec) {
        if (designSpec == null) {
            return;
        }
        List<String> forbidden = designSpec.getForbiddenPatterns();
        List<String> preferred = designSpec.getPreferredPatterns();
        if ((forbidden == null || forbidden.isEmpty()) && (preferred == null || preferred.isEmpty())) {
            return;
        }
        sb.append("## 强制设计规范（以下为设计约束，不是用户需求，违反视为严重错误）\n");
        sb.append("说明：以下禁用规则优先级【高于】一般用户需求；仅当用户在需求中明确点名某一禁用项时，才放行该一项，其余禁用项仍然生效。\n");
        if (forbidden != null && !forbidden.isEmpty()) {
            sb.append("【禁用】\n");
            for (String item : forbidden) {
                sb.append("- ").append(item).append("\n");
            }
        }
        if (preferred != null && !preferred.isEmpty()) {
            sb.append("【推荐】\n");
            for (String item : preferred) {
                sb.append("- ").append(item).append("\n");
            }
        }
        sb.append("\n");
    }

    /**
     * 注入运行时召回的受控设计证据（阶段三，见计划 3.5）。
     * <p>仅注入<b>后验指纹</b>来源的结构化摘要作为参考范例（先验静态规范已在上方注入）；
     * 明确声明「仅供参考、不得覆盖上面的强制约束与安全规则」。
     */
    private void appendEvidence(StringBuilder sb, DesignEvidence evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        for (DesignEvidence.Item item : evidence.getItems()) {
            // 跳过静态规范来源（先验已在上方注入，避免重复）
            String st = item.getSourceType();
            boolean fromFingerprint = st != null
                    && (st.contains("FINGERPRINT"));
            if (!fromFingerprint) {
                continue;
            }
            appendBullets(body, "布局参考", item.getLayoutPatterns());
            appendBullets(body, "组件/推荐参考", item.getPreferredPatterns());
            appendBullets(body, "交互参考", item.getInteractionPatterns());
        }
        if (body.length() == 0) {
            return;
        }
        sb.append("## 参考设计证据（来自精选页面召回，仅供参考，【不得】覆盖上面的强制约束、技术栈与安全规则）\n");
        sb.append(body);
        sb.append("\n");
    }

    private void appendBullets(StringBuilder sb, String label, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                sb.append("- ").append(label).append("：").append(item).append("\n");
            }
        }
    }

    /**
     * 上一轮质检未通过时，按对象字段注入 issues 清单（含 suggestion，携带修复方向）。
     */
    private void appendCriticIssues(StringBuilder sb, CriticReport criticReport) {
        if (criticReport == null || criticReport.isPassed()
                || criticReport.getIssues() == null || criticReport.getIssues().isEmpty()) {
            return;
        }
        sb.append("## 上一轮质检问题清单（必须逐条修复，不要盲目重写）\n");
        for (CriticReport.Issue issue : criticReport.getIssues()) {
            sb.append("- [").append(nullToEmpty(issue.getSeverity())).append("]")
                    .append("[").append(nullToEmpty(issue.getCategory())).append("] ");
            if (issue.getFile() != null && !issue.getFile().isBlank()) {
                sb.append(issue.getFile()).append("：");
            }
            sb.append(nullToEmpty(issue.getDescription()));
            if (issue.getSuggestion() != null && !issue.getSuggestion().isBlank()) {
                sb.append("（修复建议：").append(issue.getSuggestion()).append("）");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
