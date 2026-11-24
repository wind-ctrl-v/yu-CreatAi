package com.yu.yuaifather.agent.pipeline;

import cn.hutool.core.io.FileUtil;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yu.yuaifather.agent.service.AgentAiService;
import com.yu.yuaifather.observability.LlmCallAttribution;
import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.service.design.DesignLintChecker;
import com.yu.yuaifather.utils.JsonExtractor;
import com.yu.yuaifather.model.dto.agent.CriticReport;
import com.yu.yuaifather.model.dto.agent.DesignEvidence;
import com.yu.yuaifather.model.dto.agent.GenerationPlan;
import com.yu.yuaifather.model.dto.agent.PromptContext;
import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.service.FingerprintFeedbackService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    private static final Pattern IMG_PLACEHOLDER = Pattern.compile("@@IMG:[^@]+?@@|\\{\\{IMG:[^{}]+}}");
    private static final int MAX_REVIEW_FILES = 8;
    private static final int MAX_REVIEW_FILE_CHARS = 12_000;
    private static final int MAX_REVIEW_TOTAL_CHARS = 60_000;
    private static final long MAX_REVIEW_SOURCE_BYTES = 512L * 1024L;

    private final AgentAiService agentAiService;
    private final DesignLintChecker designLintChecker;
    private final FingerprintFeedbackService feedbackService;

    @Autowired
    public CriticAgent(AgentAiService agentAiService,
                       DesignLintChecker designLintChecker,
                       @Autowired(required = false) FingerprintFeedbackService feedbackService) {
        this.agentAiService = agentAiService;
        this.designLintChecker = designLintChecker;
        this.feedbackService = feedbackService;
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

                // 确定性反模式扫描（零 LLM），结果同时用于：① 注入 LLM 参考；② 客观度量与强制判定
                DesignLintChecker.LintResult lint = designLintChecker.check(
                        ctx.getAppId(), ctx.getBlackboard().getDesignSpec());

                // 将静态检查结果（P0 缺失、P1 缺失、占位符残留）传给 LLM 参考
                reviewInput.put("missingP0Files", String.join(",", issues.missingP0));
                reviewInput.put("missingP1Files", String.join(",", issues.missingP1));
                reviewInput.put("placeholderResidualFiles", String.join(",", issues.placeholderResidual));
                // 反模式扫描计数与悬空变量摘要
                reviewInput.set("designLintCounts", JsonExtractor.mapper().valueToTree(lint.getCounts()));
                reviewInput.set("danglingVars", JsonExtractor.mapper().valueToTree(lint.getDanglingVars()));
                reviewInput.put("entryImportsGlobalStyles",
                        lint.isEntryImportsVariables() && lint.isEntryImportsGlobal());

                GenerationPlan plan = ctx.getBlackboard().getPlan();
                if (plan != null) {
                    reviewInput.set("plan", JsonExtractor.mapper().valueToTree(plan));
                }
                ArrayNode generated = reviewInput.putArray("generatedFiles");
                ctx.getBlackboard().getGeneratedFiles().keySet().forEach(generated::add);

                PromptContext promptContext = ctx.getBlackboard().getPromptContext();
                if (promptContext != null) {
                    ObjectNode personalization = reviewInput.putObject("personalization");
                    putIfNotBlank(personalization, "userPreference", promptContext.getUserPreferenceSummary());
                    putIfNotBlank(personalization, "projectBrand", promptContext.getProjectBrandSummary());
                    putIfNotBlank(personalization, "designSpec", promptContext.getDesignSpecSummary());
                }
                appendReviewSourceFiles(reviewInput, ctx.getAppId(), plan,
                        ctx.getBlackboard().getGeneratedFiles().keySet());

                // 调用 LLM 进行综合评分，失败时退化为纯静态判定
                CriticReport report;
                try {
                    report = JsonExtractor.parseWithRetry(
                            () -> {
                                try (var ignored = LlmCallAttribution.open(
                                        ctx.getGenerationId(), ctx.getAppId(), AGENT_NAME)) {
                                    return agentAiService.review(reviewInput.toString());
                                }
                            },
                            CriticReport.class);
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

                // 合并确定性扫描命中为结构化 issues（命中即客观判定，不依赖 LLM 主观判断）
                mergeLintIssues(report, lint, ctx.getAppId());
                enforceDesignSimilarityGate(report, ctx.getAppId());

                ctx.getBlackboard().putCriticReport(report);

                // ★ 阶段三：CriticAgent 反哺指纹质量
                        if (feedbackService != null) {
                            try {
                                DesignEvidence evidence = ctx.getBlackboard().getDesignEvidence();
                                if (evidence != null && !evidence.isEmpty()) {
                                    List<Long> fpIds = evidence.getItems().stream()
                                            .map(DesignEvidence.Item::getSourceId)
                                            .filter(id -> id != null && id.matches("\\d+"))
                                            .map(Long::parseLong)
                                            .collect(Collectors.toList());
                                    double qualityScore = report.getScore() / 100.0;
                                    Long ragTraceId = ctx.getBlackboard().getRagTraceId();
                                    feedbackService.feedbackFingerprintQuality(
                                            fpIds, qualityScore, ragTraceId,
                                            JsonExtractor.mapper().writeValueAsString(report));
                                    log.debug("CriticAgent 反哺指纹质量 fpIds={} qualityScore={} ragTraceId={}",
                                            fpIds.size(), String.format("%.3f", qualityScore), ragTraceId);
                                }
                            } catch (Exception fbEx) {
                                // 反哺失败不影响质检主流程
                                log.warn("CriticAgent 指纹反哺失败 appId={} msg={}",
                                        ctx.getAppId(), fbEx.getMessage());
                            }
                        }

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

    private void putIfNotBlank(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    /**
     * 注入关键源码片段供 LLM 做审美与语义检查。候选路径来自系统计划和已生成文件，
     * 读取前仍执行根目录约束、扩展名、文件大小和总字符预算校验。
     */
    private void appendReviewSourceFiles(ObjectNode reviewInput, Long appId, GenerationPlan plan,
                                         Set<String> generatedFiles) {
        Path root = projectRoot(appId);
        if (!FileUtil.exist(root.toFile())) {
            return;
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add("src/App.vue");
        if (plan != null && plan.getRouterConfig() != null) {
            plan.getRouterConfig().stream()
                    .map(GenerationPlan.RouteSpec::getComponent)
                    .filter(path -> path != null && !path.isBlank())
                    .forEach(candidates::add);
        }
        candidates.add("src/styles/variables.css");
        candidates.add("src/styles/global.css");
        if (generatedFiles != null) {
            generatedFiles.stream()
                    .filter(this::isReviewableSourcePath)
                    .forEach(candidates::add);
        }

        ObjectNode sourceFiles = reviewInput.putObject("sourceFiles");
        int fileCount = 0;
        int totalChars = 0;
        for (String relative : candidates) {
            if (fileCount >= MAX_REVIEW_FILES || totalChars >= MAX_REVIEW_TOTAL_CHARS
                    || !isReviewableSourcePath(relative)) {
                continue;
            }
            Path target;
            try {
                target = root.resolve(relative).normalize();
            } catch (Exception e) {
                continue;
            }
            if (!target.startsWith(root) || !java.nio.file.Files.isRegularFile(target)) {
                continue;
            }
            try {
                if (java.nio.file.Files.size(target) > MAX_REVIEW_SOURCE_BYTES) {
                    continue;
                }
                String content = FileUtil.readString(target.toFile(), StandardCharsets.UTF_8);
                int allowed = Math.min(MAX_REVIEW_FILE_CHARS, MAX_REVIEW_TOTAL_CHARS - totalChars);
                if (allowed <= 0) {
                    break;
                }
                String excerpt;
                if (content.length() <= allowed) {
                    excerpt = content;
                } else {
                    String suffix = "\n/* [源码摘要已截断] */";
                    int bodyLength = Math.max(0, allowed - suffix.length());
                    excerpt = content.substring(0, bodyLength) + suffix;
                }
                sourceFiles.put(relative.replace('\\', '/'), excerpt);
                totalChars += excerpt.length();
                fileCount++;
            } catch (Exception e) {
                log.debug("CriticAgent 读取审查源码失败，跳过 file={}", target, e);
            }
        }
    }

    private boolean isReviewableSourcePath(String relative) {
        if (relative == null || relative.isBlank() || relative.contains("\\")) {
            return false;
        }
        String lower = relative.toLowerCase();
        return (lower.endsWith(".vue") || lower.endsWith(".html")
                || lower.endsWith(".css") || lower.endsWith(".js"))
                && !lower.contains("node_modules/") && !lower.contains("dist/");
    }

    /**
     * 固定模板属于阻塞问题；组件重复与品牌缺失同时出现时同样阻塞。
     * 服务端门禁不信任模型给出的 passed 字段。
     */
    private void enforceDesignSimilarityGate(CriticReport report, Long appId) {
        if (report == null || report.getIssues() == null) {
            return;
        }
        boolean fixedTemplate = false;
        boolean repeatedComponents = false;
        boolean missingBrandIdentity = false;
        for (CriticReport.Issue issue : report.getIssues()) {
            if (issue == null || !"design_similarity".equalsIgnoreCase(issue.getCategory())) {
                continue;
            }
            String description = issue.getDescription() == null ? "" : issue.getDescription();
            fixedTemplate |= description.contains("[fixed-template]")
                    || description.contains("固定模板") || description.contains("机械套用");
            repeatedComponents |= description.contains("[component-repetition]")
                    || description.contains("组件重复") || description.contains("圆角卡片");
            missingBrandIdentity |= description.contains("[brand-identity]")
                    || description.contains("品牌辨识度") || description.contains("没有体现");
        }
        if (fixedTemplate || (repeatedComponents && missingBrandIdentity)) {
            report.setPassed(false);
            if (report.getScore() > 75) {
                report.setScore(75);
            }
            log.info("CriticAgent 同质化门禁未通过 appId={} fixedTemplate={} repeated={} missingBrand={}",
                    appId, fixedTemplate, repeatedComponents, missingBrandIdentity);
        }
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

        // 占位符残留检查：扫描文本代码文件中未替换的 @@IMG:...@@（兼容旧 {{IMG:...}}）
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
            } catch (Exception e) {
                log.trace("二进制文件读取异常，跳过检查", e);
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

    /**
     * 把 DesignLintChecker 的确定性命中合并为结构化 issues 并回填到 report。
     * <p>
     * 按可修复归属归类为 {@code category="code"} / {@code category="style"}，便于 GenerationOrchestrator 路由修复：
     * <ul>
     *   <li>缺少全局样式 import / 组件悬空变量 → P0，且强制 {@code passed=false}；</li>
     *   <li>毛玻璃 / 装饰渐变 / 硬编码 px / 硬编码颜色 / 浮夸 scale → P1，记录但不单独强制失败
     *       （由 LLM 评分与累计决定，避免轻微命中即反复重试）。</li>
     * </ul>
     */
    private void mergeLintIssues(CriticReport report, DesignLintChecker.LintResult lint, Long appId) {
        if (lint == null || !lint.hasAnyHit()) {
            return;
        }
        List<CriticReport.Issue> merged = new ArrayList<>();
        if (report.getIssues() != null) {
            merged.addAll(report.getIssues());
        }
        for (Map.Entry<String, Integer> e : lint.getCounts().entrySet()) {
            String pattern = e.getKey();
            int count = e.getValue();
            boolean isP0 = DesignLintChecker.P_MISSING_IMPORT.equals(pattern)
                    || DesignLintChecker.P_DANGLING_VAR.equals(pattern);
            // 命中样本文件（取首个）便于定位
            String sampleFile = lint.getHits().stream()
                    .filter(h -> h.getPattern().equals(pattern))
                    .map(DesignLintChecker.Hit::getFile)
                    .findFirst().orElse(null);
            String category = categoryOf(pattern, sampleFile);
            merged.add(CriticReport.Issue.builder()
                    .severity(isP0 ? "P0" : "P1")
                    .category(category)
                    .file(sampleFile)
                    .description("设计反模式命中 [" + pattern + "] x" + count)
                    .suggestion(suggestionOf(pattern))
                    .build());
        }
        report.setIssues(merged);

        if (lint.hasP0()) {
            report.setPassed(false);
            if (report.getScore() > 70) {
                report.setScore(60);
            }
            log.warn("CriticAgent 设计 P0 命中强制失败 appId={} danglingVars={} importsVars={} importsGlobal={}",
                    appId, lint.getDanglingVars(), lint.isEntryImportsVariables(), lint.isEntryImportsGlobal());
        }
    }

    /**
     * 按可修复归属分类，供 Orchestrator 正确选择 CoderAgent / StyleAgent。
     */
    private String categoryOf(String pattern, String sampleFile) {
        if (DesignLintChecker.P_MISSING_IMPORT.equals(pattern)) {
            return "code";
        }
        if (DesignLintChecker.P_DANGLING_VAR.equals(pattern)) {
            return sampleFile != null && sampleFile.replace('\\', '/').startsWith("src/styles/")
                    ? "style" : "code";
        }
        return "style";
    }

    /** 针对各反模式给出固定修复方向。 */
    private String suggestionOf(String pattern) {
        switch (pattern) {
            case DesignLintChecker.P_MISSING_IMPORT:
                return "在入口文件 src/main.js 中 import './styles/variables.css' 与 './styles/global.css'";
            case DesignLintChecker.P_DANGLING_VAR:
                return "悬空变量：组件只能引用 variables.css 中已定义的 var(--xxx)，请改用 tokenGroups 清单内的变量名";
            case DesignLintChecker.P_BACKDROP:
                return "移除 backdrop-filter 毛玻璃效果，改用纯色或轻阴影区分层级";
            case DesignLintChecker.P_GRADIENT:
                return "移除纯装饰性渐变背景，改用纯色 token";
            case DesignLintChecker.P_HARDCODED_PX:
                return "用间距/字号/圆角 token（var(--space-*)/var(--font-size-*)/var(--radius-*)）替换硬编码 px";
            case DesignLintChecker.P_HARDCODED_COLOR:
                return "用 var(--color-*) 替换硬编码颜色";
            case DesignLintChecker.P_SCALE:
                return "hover 缩放不超过 1.05，或改用颜色/阴影变化";
            default:
                return "请按设计规范修正";
        }
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
        /** 存在未替换的 @@IMG:...@@ 占位符的文件路径列表。 */
        final List<String> placeholderResidual = new ArrayList<>();
    }
}
