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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CriticAgent：综合静态检查（占位符残留、必备文件）+ LLM 评分，输出 CriticReport。
 *
 * <h3>必备文件分级</h3>
 * <ul>
 *   <li><b>P0 核心文件</b>：缺失直接判定 {@code passed=false}，触发 Coder 重新生成。
 *       这些文件是 Vue 项目运行的最低要求，不存在就无法启动。</li>
 *   <li><b>P1 推荐文件</b>：缺失仅记录 warning 并向 LLM 报告，不强制 passed=false，
 *       避免可选文件（如全局样式）导致 Critic 循环无法终止、浪费 LLM 调用次数。</li>
 * </ul>
 */
@Slf4j
@Component
public class CriticAgent implements Agent {

    public static final String AGENT_NAME = "CriticAgent";

    /**
     * P0 核心文件：缺失则强制 passed=false，要求 CoderAgent 重新生成。
     * 这些文件是 Vue 项目可运行的最低要求。
     */
    private static final String[] REQUIRED_FILES_P0 = {
            "package.json",
            "index.html",
            "src/main.js",
            "src/App.vue"
    };

    /**
     * P1 推荐文件：缺失仅记录 warning 并通知 LLM，不强制 passed=false。
     * CoderAgent 不一定每次都生成这些文件，不应因此触发无意义的重试循环。
     */
    private static final String[] RECOMMENDED_FILES_P1 = {
            "vite.config.js",
            "src/styles/variables.css",
            "src/styles/global.css"
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

                // 将静态检查结果（P0 缺失、P1 缺失、占位符残留）传给 LLM 参考
                reviewInput.put("missingP0Files", String.join(",", issues.missingP0));
                reviewInput.put("missingP1Files", String.join(",", issues.missingP1));
                reviewInput.put("placeholderResidualFiles", String.join(",", issues.placeholderResidual));

                GenerationPlan plan = ctx.getBlackboard().getPlan();
                if (plan != null) {
                    reviewInput.set("plan", JsonExtractor.mapper().valueToTree(plan));
                }
                ArrayNode generated = reviewInput.putArray("generatedFiles");
                ctx.getBlackboard().getGeneratedFiles().keySet().forEach(generated::add);

                // 调用 LLM 进行综合评分，失败时退化为纯静态判定
                CriticReport report;
                try {
                    String raw = agentAiService.review(reviewInput.toString());
                    report = JsonExtractor.parse(raw, CriticReport.class);
                } catch (Exception llmFail) {
                    log.warn("CriticAgent LLM 评分失败，退化为静态判定 appId={} msg={}",
                            ctx.getAppId(), llmFail.getMessage());
                    report = buildFallbackReport(issues);
                }

                // P0 文件缺失或有占位符残留 → 强制 passed=false（静态规则优先于 LLM 结论）
                boolean hasP0Issue = !issues.missingP0.isEmpty() || !issues.placeholderResidual.isEmpty();
                if (hasP0Issue) {
                    report.setPassed(false);
                    if (report.getScore() > 70) {
                        report.setScore(60);
                    }
                    log.warn("CriticAgent P0 问题强制失败 appId={} missingP0={} placeholderResidual={}",
                            ctx.getAppId(), issues.missingP0, issues.placeholderResidual);
                }

                // P1 文件缺失只记录日志，不影响 passed
                if (!issues.missingP1.isEmpty()) {
                    log.info("CriticAgent P1 推荐文件缺失（不阻塞） appId={} missingP1={}",
                            ctx.getAppId(), issues.missingP1);
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

    /**
     * 执行静态检查：分别检测 P0 核心文件缺失、P1 推荐文件缺失、图片占位符残留。
     */
    private StaticIssues runStaticChecks(Long appId) {
        StaticIssues issues = new StaticIssues();
        Path root = projectRoot(appId);

        // 目录不存在：仅 P0 文件计入 missing（P1 文件不存在是正常情况）
        if (!FileUtil.exist(root.toFile())) {
            for (String f : REQUIRED_FILES_P0) {
                issues.missingP0.add(f);
            }
            return issues;
        }

        // P0 文件检查
        for (String required : REQUIRED_FILES_P0) {
            if (!FileUtil.exist(root.resolve(required).toFile())) {
                issues.missingP0.add(required);
            }
        }

        // P1 文件检查（只记录，不参与 passed 判定）
        for (String recommended : RECOMMENDED_FILES_P1) {
            if (!FileUtil.exist(root.resolve(recommended).toFile())) {
                issues.missingP1.add(recommended);
            }
        }

        // 占位符残留检查：扫描文本代码文件中未替换的 {{IMG:...}}
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

    /**
     * LLM 评分失败时的纯静态兜底报告。
     * <p>
     * 得分计算：仅基于 P0 问题（每个 -30 分），P1 缺失不扣分。
     */
    private CriticReport buildFallbackReport(StaticIssues issues) {
        int p0Count = issues.missingP0.size() + issues.placeholderResidual.size();
        int score = Math.max(0, 100 - p0Count * 30);
        return CriticReport.builder()
                .passed(score >= 80)
                .score(score)
                .summary("静态检查兜底报告（LLM 评分不可用）")
                .issues(List.of())
                .build();
    }

    private Path projectRoot(Long appId) {
        String dir = "vue_project_" + appId;
        return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, dir).toAbsolutePath().normalize();
    }

    /**
     * 静态检查结果，区分 P0/P1 文件缺失与占位符残留。
     */
    private static class StaticIssues {
        /** P0 核心文件缺失（强制 passed=false）。 */
        final List<String> missingP0 = new ArrayList<>();
        /** P1 推荐文件缺失（记录日志，不阻塞流水线）。 */
        final List<String> missingP1 = new ArrayList<>();
        /** 存在未替换的 {{IMG:...}} 占位符的文件路径列表。 */
        final List<String> placeholderResidual = new ArrayList<>();
    }
}
