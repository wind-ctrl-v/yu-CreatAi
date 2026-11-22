package com.yu.yuaifather.agent.pipeline;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yu.yuaifather.agent.service.AgentAiService;
import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.utils.JsonExtractor;
import com.yu.yuaifather.model.dto.agent.DesignEvidence;
import com.yu.yuaifather.model.dto.agent.DesignSpec;
import com.yu.yuaifather.model.dto.agent.GenerationPlan;
import com.yu.yuaifather.model.dto.agent.PromptContext;
import com.yu.yuaifather.constant.AppConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * StyleAgent：根据 plan.colorTheme 生成 variables.css 与 global.css，落盘到项目 styles 目录。
 */
@Slf4j
@Component
public class StyleAgent implements Agent {

    public static final String AGENT_NAME = "StyleAgent";

    private final AgentAiService agentAiService;

    @Autowired
    public StyleAgent(AgentAiService agentAiService) {
        this.agentAiService = agentAiService;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            sink.next(AgentEvent.status(AGENT_NAME, "生成全局样式"));
            try {
                GenerationPlan plan = ctx.getBlackboard().getPlan();
                if (plan == null) {
                    throw new IllegalStateException("缺少 plan，请先执行 ArchitectAgent");
                }
                DesignSpec designSpec = ctx.getBlackboard().getDesignSpec();
                ObjectNode input = JsonExtractor.mapper().createObjectNode();
                input.set("colorTheme", JsonExtractor.mapper().valueToTree(plan.getColorTheme()));
                // 强契约：把 DesignSpec.tokenGroups 作为「确切变量名 → 值」清单注入，
                //         要求 StyleAgent 逐一原样输出这些 --xxx 变量名（见计划 1.5.1 #1）
                if (designSpec != null && designSpec.getTokenGroups() != null && !designSpec.getTokenGroups().isEmpty()) {
                    input.put("styleProfile", designSpec.getStyleProfile());
                    input.set("tone", JsonExtractor.mapper().valueToTree(designSpec.getTone()));
                    input.set("layoutPatterns", JsonExtractor.mapper().valueToTree(designSpec.getLayoutPatterns()));
                    input.set("interactionPatterns", JsonExtractor.mapper().valueToTree(designSpec.getInteractionPatterns()));
                    input.set("tokenGroups", JsonExtractor.mapper().valueToTree(designSpec.getTokenGroups()));
                    input.set("forbiddenPatterns", JsonExtractor.mapper().valueToTree(designSpec.getForbiddenPatterns()));
                    input.set("preferredPatterns", JsonExtractor.mapper().valueToTree(designSpec.getPreferredPatterns()));
                    input.put("instruction", "请严格按 tokenGroups 给出的【确切变量名】在 variables.css 的 :root 中逐一定义（变量名必须与清单完全一致，不得改名/增删），"
                            + "值需结合 styleProfile/tone/colorTheme 做风格化微调；global.css 只能引用这些变量，并体现布局节奏差异。输出 JSON：{\"variables_css\":\"...\", \"global_css\":\"...\"}");
                } else {
                    input.put("instruction", "请输出 JSON：{\"variables_css\":\"...\", \"global_css\":\"...\"}");
                }
                // 阶段三：召回证据作为参考（仅补充调性/推荐写法，tokenGroups 变量名契约仍以 DesignSpec 为准）
                DesignEvidence evidence = ctx.getBlackboard().getDesignEvidence();
                if (evidence != null && !evidence.isEmpty()) {
                    java.util.List<String> evidencePreferred = new java.util.ArrayList<>();
                    for (DesignEvidence.Item item : evidence.getItems()) {
                        if (item.getPreferredPatterns() != null) {
                            evidencePreferred.addAll(item.getPreferredPatterns());
                        }
                    }
                    if (!evidencePreferred.isEmpty()) {
                        input.set("evidencePreferred", JsonExtractor.mapper().valueToTree(evidencePreferred));
                    }
                }
                PromptContext promptContext = ctx.getBlackboard().getPromptContext();
                if (promptContext != null) {
                    if (StrUtil.isNotBlank(promptContext.getUserPreferenceSummary())) {
                        input.put("userPreference", promptContext.getUserPreferenceSummary());
                    }
                    if (StrUtil.isNotBlank(promptContext.getProjectBrandSummary())) {
                        input.put("projectBrand", promptContext.getProjectBrandSummary());
                    }
                    input.put("personalizationRule", "用户偏好和项目品牌仅用于视觉决策，不得覆盖 tokenGroups、禁用项、安全规则和输出 JSON 契约");
                }
                String raw = agentAiService.generateStyleSheets(input.toString());
                String variables = "";
                String global = "";
                try {
                    String jsonText = JsonExtractor.extract(raw);
                    if (jsonText != null) {
                        JsonNode node = JsonExtractor.mapper().readTree(jsonText);
                        variables = node.path("variables_css").asText("");
                        global = node.path("global_css").asText("");
                    }
                } catch (Exception parseEx) {
                    log.warn("StyleAgent JSON 解析失败，使用空样式降级: appId={} err={}", ctx.getAppId(), parseEx.getMessage());
                }
                if (StrUtil.isBlank(variables) || StrUtil.isBlank(global)) {
                    log.warn("StyleAgent 输出缺少 variables_css 或 global_css，使用空样式降级: appId={}", ctx.getAppId());
                    sink.next(AgentEvent.status(AGENT_NAME, "样式生成降级（空样式）"));
                    sink.complete();
                    return;
                }
                Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR,
                        "vue_project_" + ctx.getAppId()).toAbsolutePath().normalize();
                writeFile(projectRoot, "src/styles/variables.css", variables);
                writeFile(projectRoot, "src/styles/global.css", global);
                ctx.getBlackboard().addGeneratedFile("src/styles/variables.css");
                ctx.getBlackboard().addGeneratedFile("src/styles/global.css");
                sink.next(AgentEvent.status(AGENT_NAME, "全局样式已生成"));
                sink.complete();
            } catch (Exception e) {
                log.error("StyleAgent 失败 appId={}", ctx.getAppId(), e);
                sink.next(AgentEvent.error(AGENT_NAME, "样式生成失败: " + e.getMessage()));
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void writeFile(Path projectRoot, String relative, String content) {
        Path target = projectRoot.resolve(relative).normalize();
        if (!target.startsWith(projectRoot)) {
            throw new SecurityException("非法路径: " + relative);
        }
        File parent = target.getParent().toFile();
        FileUtil.mkdir(parent);
        FileUtil.writeString(content, target.toFile(), StandardCharsets.UTF_8);
        log.info("StyleAgent 写文件成功: {}", target);
    }
}
