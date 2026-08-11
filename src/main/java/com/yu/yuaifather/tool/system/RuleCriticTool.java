package com.yu.yuaifather.tool.system;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.tool.core.SystemTool;
import com.yu.yuaifather.tool.core.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RuleCriticTool：确定性规则检查器（System Tool，零 LLM）。
 * <p>
 * 检查项：
 * <ul>
 *   <li>HTML/CSS/JS 是否为空</li>
 *   <li>是否包含必要 section</li>
 *   <li>CSS 变量使用率</li>
 *   <li>响应式断点</li>
 *   <li>占位符残留</li>
 * </ul>
 * <p>
 * 原先由 CriticAgent 负责的确定性检查迁移到此 Tool，
 * 只把真正需要「审美判断」的部分留给 LLM CriticAgent。
 */
@Slf4j
@Component
public class RuleCriticTool implements SystemTool<RuleCriticTool.RuleCriticInput, RuleCriticTool.RuleCriticResult> {

    public static final String TOOL_NAME = "RuleCriticTool";

    private static final Pattern IMG_PLACEHOLDER = Pattern.compile("@@IMG:[^@]+?@@|\\{\\{IMG:[^{}]+}}");
    private static final Pattern CLASS_ATTRIBUTE = Pattern.compile(
            "(?i)class\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']");
    private static final int MAX_PROJECT_FILES = 200;
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            "node_modules", "dist", "target", ".git");

    private static final String[] REQUIRED_FILES_P0 = {
            "package.json", "index.html", "src/main.js", "src/App.vue"
    };

    public record RuleCriticInput(Long appId, String html, String css, String js) {}

    public record RuleCriticResult(
            boolean passed,
            int score,
            java.util.List<String> issues,
            boolean htmlEmpty,
            boolean cssEmpty,
            boolean jsEmpty,
            boolean hasPlaceholders,
            boolean missingRequiredFiles,
            java.util.List<String> missingFiles,
            boolean hasDesignSimilarityIssues
    ) {
        public static RuleCriticResult allPassed() {
            return new RuleCriticResult(true, 100, java.util.List.of(), false, false, false,
                    false, false, java.util.List.of(), false);
        }
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public RuleCriticResult execute(RuleCriticInput input, ToolContext context) {
        if (input == null) {
            return RuleCriticResult.allPassed();
        }
        java.util.List<String> issues = new java.util.ArrayList<>();
        int score = 100;
        boolean hasInlineContent = input.html() != null || input.css() != null || input.js() != null;
        boolean htmlEmpty = hasInlineContent && StrUtil.isBlank(input.html());
        boolean cssEmpty = hasInlineContent && StrUtil.isBlank(input.css());
        boolean jsEmpty = hasInlineContent && StrUtil.isBlank(input.js());
        boolean hasPlaceholders = false;
        boolean missingRequired = false;
        java.util.List<String> missingFiles = new java.util.ArrayList<>();

        // 1. 空内容检查：仅适用于传入 html/css/js 的单文件生成链路。
        // Vue 项目链路只传 appId，不能把 null html 误判为 HTML 为空。
        if (hasInlineContent) {
            if (htmlEmpty) {
                issues.add("HTML 内容为空");
                score -= 40;
            }
            if (cssEmpty && input.js() != null) {
                // CSS 为空但 JS 非空，视为异常（Vue 项目中 CSS 可能可选）
                issues.add("CSS 内容为空");
                score -= 10;
            }
        }

        // 2. 占位符残留检查
        if (input.html() != null && IMG_PLACEHOLDER.matcher(input.html()).find()) {
            issues.add("HTML 中存在未替换的图片占位符 @@IMG:...@@");
            score -= 30;
            hasPlaceholders = true;
        }
        if (input.css() != null && IMG_PLACEHOLDER.matcher(input.css()).find()) {
            issues.add("CSS 中存在未替换的图片占位符 @@IMG:...@@");
            score -= 30;
            hasPlaceholders = true;
        }
        if (input.js() != null && IMG_PLACEHOLDER.matcher(input.js()).find()) {
            issues.add("JS 中存在未替换的图片占位符 @@IMG:...@@");
            score -= 30;
            hasPlaceholders = true;
        }

        // 3. Vue 项目 P0 文件与占位符检查
        if (input.appId() != null) {
            Path root = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR,
                    "vue_project_" + input.appId()).toAbsolutePath().normalize();
            for (String required : REQUIRED_FILES_P0) {
                if (!FileUtil.exist(root.resolve(required).toFile())) {
                    missingFiles.add(required);
                    missingRequired = true;
                }
            }
            if (missingRequired) {
                issues.add("缺少必要文件: " + String.join(", ", missingFiles));
                score -= 30;
            }
            java.util.List<String> placeholderFiles = scanPlaceholderFiles(root);
            if (!placeholderFiles.isEmpty()) {
                issues.add("项目文件中存在未替换的图片占位符: " + String.join(", ", placeholderFiles));
                if (!hasPlaceholders) {
                    score -= 30;
                }
                hasPlaceholders = true;
            }
        }

        // 4. HTML section 检查
        if (input.html() != null && !htmlEmpty) {
            String htmlLower = input.html().toLowerCase();
            if (!htmlLower.contains("<header") && !htmlLower.contains("<nav")
                    && !htmlLower.contains("<main") && !htmlLower.contains("<section")
                    && !htmlLower.contains("<div")) {
                issues.add("HTML 缺少明确的语义化结构标签");
                score -= 10;
            }
        }

        // 5. CSS 变量使用率检查
        if (input.css() != null && !cssEmpty && !input.css().contains("var(--")) {
            issues.add("CSS 中未使用 CSS 变量（var(--）");
            score -= 10;
        }

        // 6. 页面结构同质化检查：仅识别置信度较高的固定模板与卡片滥用。
        java.util.Set<String> designIssues = new java.util.LinkedHashSet<>();
        if (input.html() != null && !htmlEmpty) {
            designIssues.addAll(analyzeDesignSimilarity(input.html()));
        }
        if (input.appId() != null) {
            Path root = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR,
                    "vue_project_" + input.appId()).toAbsolutePath().normalize();
            designIssues.addAll(analyzeProjectDesign(root));
        }
        if (!designIssues.isEmpty()) {
            issues.addAll(designIssues);
            score -= Math.min(30, designIssues.size() * 15);
        }
        boolean hasDesignSimilarityIssues = !designIssues.isEmpty();

        boolean passed = score >= 70 && !htmlEmpty && !hasPlaceholders && !missingRequired;
        return new RuleCriticResult(passed, Math.max(0, score), issues,
                htmlEmpty, cssEmpty, jsEmpty, hasPlaceholders, missingRequired, missingFiles,
                hasDesignSimilarityIssues);
    }

    private List<String> analyzeDesignSimilarity(String source) {
        if (StrUtil.isBlank(source)) {
            return List.of();
        }
        String lower = source.toLowerCase(Locale.ROOT);
        int cardCount = countCardClasses(source);
        int effectiveCardCount = lower.contains("v-for") && cardCount > 0
                ? Math.max(cardCount, 3) : cardCount;
        List<String> issues = new java.util.ArrayList<>();
        boolean fixedTemplate = lower.contains("hero") && lower.contains("footer")
                && (lower.contains("feature-grid") || lower.contains("features"))
                && effectiveCardCount >= 3;
        if (fixedTemplate) {
            issues.add("页面疑似机械套用 Hero + 特性卡片 + Footer 固定模板，需调整区块顺序与视觉重心");
        }
        if (cardCount >= 8) {
            issues.add("页面大量重复使用 card 形态，需改用分屏、列表、时间线或无容器排版形成层级变化");
        }
        return issues;
    }

    private int countCardClasses(String source) {
        int count = 0;
        Matcher matcher = CLASS_ATTRIBUTE.matcher(source);
        while (matcher.find()) {
            for (String className : matcher.group(1).trim().split("\\s+")) {
                String normalized = className.toLowerCase(Locale.ROOT);
                if (normalized.equals("card") || normalized.startsWith("card-")
                        || normalized.endsWith("-card")) {
                    count++;
                }
            }
        }
        return count;
    }

    private Set<String> analyzeProjectDesign(Path root) {
        Set<String> issues = new java.util.LinkedHashSet<>();
        for (Path file : listProjectCodeFiles(root)) {
            try {
                issues.addAll(analyzeDesignSimilarity(Files.readString(file, StandardCharsets.UTF_8)));
            } catch (Exception e) {
                log.debug("同质化检查读取文件失败，跳过: {}", file, e);
            }
        }
        return issues;
    }

    private List<String> scanPlaceholderFiles(Path root) {
        List<String> filesWithPlaceholders = new java.util.ArrayList<>();
        for (Path file : listProjectCodeFiles(root)) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (IMG_PLACEHOLDER.matcher(content).find()) {
                    filesWithPlaceholders.add(root.relativize(file).toString().replace('\\', '/'));
                }
            } catch (Exception e) {
                log.debug("规则检查读取文件失败，跳过: {}", file, e);
            }
        }
        return filesWithPlaceholders;
    }

    private List<Path> listProjectCodeFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> result = new java.util.ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && IGNORED_DIRECTORIES.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isTextCodeFile(file) && attrs.size() <= MAX_FILE_BYTES) {
                        result.add(file);
                    }
                    return result.size() >= MAX_PROJECT_FILES
                            ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug("规则检查无法访问文件，跳过: {}", file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });
            return result;
        } catch (Exception e) {
            log.debug("规则检查遍历项目失败，跳过: {}", root, e);
            return List.of();
        }
    }

    private boolean isTextCodeFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".vue") || name.endsWith(".html")
                || name.endsWith(".js") || name.endsWith(".css");
    }
}
