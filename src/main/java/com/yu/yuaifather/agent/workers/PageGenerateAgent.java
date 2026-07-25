package com.yu.yuaifather.agent.workers;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yu.yuaifather.agent.service.AgentAiService;
import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.model.dto.agent.DesignSpec;
import com.yu.yuaifather.model.dto.agent.GenerationPlan;
import com.yu.yuaifather.model.dto.agent.RequirementDoc;
import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.generation.domain.GeneratedArtifact;
import com.yu.yuaifather.utils.JsonExtractor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PageGenerateAgent：低成本/标准模式下一次 LLM 调用生成完整的 HTML/CSS/JS。
 * <p>
 * 接收 PromptContext 中的 fullPrompt 作为输入，输出页面三件套。
 * 与 CoderAgent + StyleAgent 二段式架构互为替代：
 * <ul>
 *   <li>standard / low_cost 模式 → 使用 PageGenerateAgent（一次调用）</li>
 *   <li>high_quality 模式 → 使用 CoderAgent + StyleAgent（并发两次调用）</li>
 * </ul>
 */
@Slf4j
@Component
public class PageGenerateAgent implements Agent {

    public static final String AGENT_NAME = "PageGenerateAgent";

    private static final int MAX_FILES = 30;
    private static final int MAX_FILE_BYTES = 512 * 1024;
    private static final Set<String> REQUIRED_FILES = Set.of(
            "package.json", "index.html", "vite.config.js", "src/main.js", "src/App.vue");

    private final AgentAiService agentAiService;

    @Autowired
    public PageGenerateAgent(AgentAiService agentAiService) {
        this.agentAiService = agentAiService;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            sink.next(AgentEvent.status(AGENT_NAME, "一次性生成页面"));
            try {
                RequirementDoc requirement = ctx.getBlackboard().getRequirementDoc();
                GenerationPlan plan = ctx.getBlackboard().getPlan();
                if (requirement == null && plan == null) {
                    throw new IllegalStateException("缺少 RequirementDoc 或 GenerationPlan");
                }

                String prompt = buildPrompt(ctx);
                String raw = agentAiService.generatePage(prompt);
                PageGenerationResponse response = JsonExtractor.parse(raw, PageGenerationResponse.class);
                GeneratedArtifact artifact = saveFiles(ctx, response);
                ctx.getBlackboard().putGeneratedArtifact(artifact);

                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.STATUS_CHANGE)
                        .agentName(AGENT_NAME)
                        .content("页面生成完成，共 " + artifact.getFiles().size() + " 个文件")
                        .data(artifact)
                        .build());
                sink.complete();
            } catch (Exception e) {
                log.error("PageGenerateAgent 失败 appId={}", ctx.getAppId(), e);
                sink.next(AgentEvent.error(AGENT_NAME, "页面生成失败: " + e.getMessage()));
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String buildPrompt(AgentContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("原始需求：").append(ctx.getUserPrompt()).append("\n\n");

        RequirementDoc requirement = ctx.getBlackboard().getRequirementDoc();
        if (requirement != null) {
            sb.append("项目名称：").append(
                    StrUtil.isNotBlank(requirement.getProjectName())
                            ? requirement.getProjectName() : "未命名").append("\n");
        }

        DesignSpec designSpec = ctx.getBlackboard().getDesignSpec();
        if (designSpec != null) {
            sb.append("风格：").append(designSpec.getStyleProfile()).append("\n");
            if (designSpec.getForbiddenPatterns() != null) {
                sb.append("禁用：").append(String.join(",", designSpec.getForbiddenPatterns())).append("\n");
            }
        }

        GenerationPlan plan = ctx.getBlackboard().getPlan();
        if (plan != null) {
            sb.append("请严格按以下计划生成代码：\n")
                    .append(com.yu.yuaifather.utils.JsonExtractor.mapper()
                            .valueToTree(plan).toString()).append("\n");
        }

        return sb.toString();
    }

    private GeneratedArtifact saveFiles(AgentContext ctx, PageGenerationResponse response) throws Exception {
        if (response == null || response.getFiles() == null || response.getFiles().isEmpty()
                || response.getFiles().size() > MAX_FILES) {
            throw new IllegalArgumentException("页面文件数量无效");
        }
        if (!response.getFiles().keySet().containsAll(REQUIRED_FILES)) {
            throw new IllegalArgumentException("页面缺少最低可运行文件");
        }
        Path root = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR,
                "vue_project_" + ctx.getAppId()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        for (Map.Entry<String, String> entry : response.getFiles().entrySet()) {
            String relativePath = entry.getKey();
            String content = entry.getValue();
            if (relativePath == null || relativePath.isBlank() || relativePath.contains("\\")
                    || Path.of(relativePath).isAbsolute() || content == null
                    || content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("页面文件不合法");
            }
            Path target = root.resolve(relativePath).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("页面文件路径越界");
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            ctx.getBlackboard().addGeneratedFile(relativePath);
        }
        String entryFile = StrUtil.isBlank(response.getEntryFile()) ? "src/main.js" : response.getEntryFile();
        return GeneratedArtifact.builder()
                .generationId(ctx.getGenerationId())
                .appId(ctx.getAppId())
                .codeGenType(ctx.getCodeGenType())
                .workingDirectory(root)
                .files(List.copyOf(response.getFiles().keySet()))
                .entryFile(entryFile)
                .metadata(Map.of("producer", AGENT_NAME))
                .build();
    }

    @Data
    private static class PageGenerationResponse {
        @JsonProperty("entryFile")
        private String entryFile;
        private Map<String, String> files;
    }
}
