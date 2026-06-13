package com.yu.yuaifather.agent.workers;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yu.yuaifather.agent.AgentAiService;
import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.support.JsonExtractor;
import com.yu.yuaifather.ai.model.dto.CriticReport;
import com.yu.yuaifather.ai.model.dto.GenerationPlan;
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
import java.util.Collection;
import java.util.regex.Pattern;

/**
 * CriticAgent：综合静态检查（占位符残留、必备文件）+ LLM 评分，输出 CriticReport。
 */
@Slf4j
@Component
public class CriticAgent implements Agent {

    public static final String AGENT_NAME = "CriticAgent";

    /** 必备文件清单。 */
    private static final String[] REQUIRED_FILES = {
            "package.json", "vite.config.js", "index.html",
            "src/main.js", "src/App.vue",
            "src/styles/variables.css", "src/styles/global.css"
    };

    private static final Pattern IMG_PLACEHOLDER = Pattern.compile("\\{\\{IMG:[^{}]+}}");

    private final AgentAiService agentAiService;

    @Autowired
    public CriticAgent(AgentAiService agentAiService) {
        this.agentAiService = agentAiService;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            sink.next(AgentEvent.status(AGENT_NAME, "开始质检"));
            try {
                ObjectNode reviewInput = JsonExtractor.mapper().createObjectNode();
                StaticIssues issues = runStaticChecks(ctx.getAppId());
                reviewInput.put("missingFiles", String.join(",", issues.missing));
                reviewInput.put("placeholderResidualFiles", String.join(",", issues.placeholderResidual));
                GenerationPlan plan = ctx.getBlackboard().getPlan();
                if (plan != null) {
                    reviewInput.set("plan", JsonExtractor.mapper().valueToTree(plan));
                }
                ArrayNode generated = reviewInput.putArray("generatedFiles");
                ctx.getBlackboard().getGeneratedFiles().keySet().forEach(generated::add);

                CriticReport report;
                try {
                    String raw = agentAiService.review(reviewInput.toString());
                    report = JsonExtractor.parse(raw, CriticReport.class);
                } catch (Exception llmFail) {
                    log.warn("CriticAgent LLM 评分失败，退化为静态判定 msg={}", llmFail.getMessage());
                    report = buildFallbackReport(issues);
                }
                // 兜底：若静态层已发现 P0，强制 passed=false
                if (!issues.missing.isEmpty() || !issues.placeholderResidual.isEmpty()) {
                    report.setPassed(false);
                    if (report.getScore() > 70) {
                        report.setScore(60);
                    }
                }
                ctx.getBlackboard().putCriticReport(report);
                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.STATUS_CHANGE)
                        .agentName(AGENT_NAME)
                        .content("质检完成 score=" + report.getScore() + " passed=" + report.isPassed())
                        .data(report)
                        .build());
                sink.complete();
            } catch (Exception e) {
                log.error("CriticAgent 失败 appId={}", ctx.getAppId(), e);
                sink.next(AgentEvent.error(AGENT_NAME, "质检异常: " + e.getMessage()));
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private StaticIssues runStaticChecks(Long appId) {
        StaticIssues issues = new StaticIssues();
        Path root = projectRoot(appId);
        if (!FileUtil.exist(root.toFile())) {
            for (String f : REQUIRED_FILES) {
                issues.missing.add(f);
            }
            return issues;
        }
        // 必备文件
        for (String required : REQUIRED_FILES) {
            if (!FileUtil.exist(root.resolve(required).toFile())) {
                issues.missing.add(required);
            }
        }
        // 占位符残留
        Collection<File> files = FileUtil.loopFiles(root.toFile(), f -> {
            String ext = FileUtil.extName(f);
            return ext != null && (ext.equalsIgnoreCase("vue") || ext.equalsIgnoreCase("html")
                    || ext.equalsIgnoreCase("js") || ext.equalsIgnoreCase("css"));
        });
        for (File f : files) {
            try {
                String content = FileUtil.readString(f, StandardCharsets.UTF_8);
                if (IMG_PLACEHOLDER.matcher(content).find()) {
                    issues.placeholderResidual.add(root.relativize(f.toPath()).toString());
                }
            } catch (Exception ignore) {
                // 忽略二进制读取异常
            }
        }
        return issues;
    }

    private CriticReport buildFallbackReport(StaticIssues issues) {
        int p0 = issues.missing.size() + issues.placeholderResidual.size();
        int score = Math.max(0, 100 - p0 * 30);
        return CriticReport.builder()
                .passed(score >= 80)
                .score(score)
                .summary("静态检查兜底报告")
                .issues(java.util.List.of())
                .build();
    }

    private Path projectRoot(Long appId) {
        String dir = "vue_project_" + appId;
        return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, dir).toAbsolutePath().normalize();
    }

    private static class StaticIssues {
        final java.util.List<String> missing = new java.util.ArrayList<>();
        final java.util.List<String> placeholderResidual = new java.util.ArrayList<>();
    }
}
