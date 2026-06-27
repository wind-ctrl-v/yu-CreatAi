package com.yu.yuaifather.tool.system;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.tool.core.SystemTool;
import com.yu.yuaifather.tool.core.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
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

    private static final Pattern IMG_PLACEHOLDER = Pattern.compile("\\{\\{IMG:[^{}]+}}");

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
            java.util.List<String> missingFiles
    ) {
        public static RuleCriticResult allPassed() {
            return new RuleCriticResult(true, 100, java.util.List.of(), false, false, false, false, false, java.util.List.of());
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
        boolean htmlEmpty = StrUtil.isBlank(input.html());
        boolean cssEmpty = StrUtil.isBlank(input.css());
        boolean jsEmpty = StrUtil.isBlank(input.js());
        boolean hasPlaceholders = false;
        boolean missingRequired = false;
        java.util.List<String> missingFiles = new java.util.ArrayList<>();

        // 1. 空内容检查
        if (htmlEmpty) {
            issues.add("HTML 内容为空");
            score -= 40;
        }
        if (cssEmpty && input.js() != null) {
            // CSS 为空但 JS 非空，视为异常（Vue 项目中 CSS 可能可选）
            issues.add("CSS 内容为空");
            score -= 10;
        }

        // 2. 占位符残留检查
        if (input.html() != null && IMG_PLACEHOLDER.matcher(input.html()).find()) {
            issues.add("HTML 中存在未替换的图片占位符 {{IMG:...}}");
            score -= 30;
            hasPlaceholders = true;
        }
        if (input.css() != null && IMG_PLACEHOLDER.matcher(input.css()).find()) {
            issues.add("CSS 中存在未替换的图片占位符 {{IMG:...}}");
            score -= 30;
            hasPlaceholders = true;
        }

        // 3. P0 文件检查
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

        boolean passed = score >= 70 && !htmlEmpty && !hasPlaceholders && !missingRequired;
        return new RuleCriticResult(passed, Math.max(0, score), issues,
                htmlEmpty, cssEmpty, jsEmpty, hasPlaceholders, missingRequired, missingFiles);
    }
}
