package com.yu.yuaifather.agent.support;

import cn.hutool.core.io.FileUtil;
import com.yu.yuaifather.ai.model.dto.DesignSpec;
import com.yu.yuaifather.constant.AppConstant;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DesignLintChecker：确定性反模式扫描器（纯 Java，零 LLM）。
 * <p>
 * 对生成产物做正则扫描，输出结构化命中清单，供：
 * <ol>
 *   <li>CriticAgent 作为「静态检查」的确定性输入（命中即可直接判 P0/P1，不依赖 LLM 主观判断）；</li>
 *   <li>验收标准的客观可复现度量来源（命中数为 0 = 通过）。</li>
 * </ol>
 *
 * <h3>检测项</h3>
 * <ul>
 *   <li>{@code backdrop-filter} 毛玻璃；</li>
 *   <li>装饰性 {@code linear-gradient} / {@code radial-gradient}；</li>
 *   <li>硬编码 px（限定在 padding/margin/font-size 等属性，排除 0/1px 边框白名单）；</li>
 *   <li>浮夸 {@code transform: scale(>1.05)}；</li>
 *   <li>未走 {@code var(--xxx)} 的硬编码颜色（限定颜色相关属性）。</li>
 * </ul>
 *
 * <h3>附带校验</h3>
 * <ul>
 *   <li>入口文件 main.js 是否 import 了 variables.css / global.css（呼应计划 #2）；</li>
 *   <li>组件引用的 {@code var(--xxx)} 是否都在 {@code tokenGroups} 清单内（呼应 #1，检出悬空变量）。</li>
 * </ul>
 *
 * <p>本类只读产物、不调 LLM、不写文件。
 */
@Slf4j
@Component
public class DesignLintChecker {

    public static final String P_BACKDROP = "backdrop-filter";
    public static final String P_GRADIENT = "decorative-gradient";
    public static final String P_HARDCODED_PX = "hardcoded-px";
    public static final String P_SCALE = "overscale-hover";
    public static final String P_HARDCODED_COLOR = "hardcoded-color";
    public static final String P_DANGLING_VAR = "dangling-var";
    public static final String P_MISSING_IMPORT = "missing-global-style-import";

    private static final Pattern BACKDROP = Pattern.compile("backdrop-filter\\s*:");
    private static final Pattern GRADIENT = Pattern.compile("(?:linear|radial)-gradient\\s*\\(");
    private static final Pattern SCALE = Pattern.compile("scale\\(\\s*([0-9]*\\.?[0-9]+)");
    private static final Pattern HARDCODED_PX = Pattern.compile(
            "(?:padding|margin|font-size|border-radius|gap|line-height)[^:;{}]*:\\s*[^;{}]*?(\\d+)px");
    private static final Pattern HARDCODED_COLOR = Pattern.compile(
            "(?:color|background|background-color|border|border-color|fill|stroke|box-shadow|outline)\\s*:[^;{}]*#[0-9a-fA-F]{3,8}");
    private static final Pattern VAR_REF = Pattern.compile("var\\(\\s*(--[A-Za-z0-9-]+)");

    /**
     * 对指定 app 的生成产物执行确定性扫描。
     *
     * @param appId 应用 ID
     * @param spec  设计规范（用于 tokenGroups 悬空变量检测，可空）
     * @return 扫描结果（不会为 null）
     */
    public LintResult check(Long appId, DesignSpec spec) {
        LintResult result = new LintResult();
        Path root = projectRoot(appId);
        if (!FileUtil.exist(root.toFile())) {
            return result;
        }

        Set<String> knownVars = collectKnownVars(spec);

        Collection<File> files = FileUtil.loopFiles(root.toFile(), f -> {
            String ext = FileUtil.extName(f);
            return ext != null && (ext.equalsIgnoreCase("vue") || ext.equalsIgnoreCase("css")
                    || ext.equalsIgnoreCase("js") || ext.equalsIgnoreCase("html"));
        });

        for (File f : files) {
            String rel = root.relativize(f.toPath()).toString().replace('\\', '/');
            boolean styleOwned = rel.startsWith("src/styles/");
            boolean variablesCss = rel.equals("src/styles/variables.css");
            String content;
            try {
                content = FileUtil.readString(f, StandardCharsets.UTF_8);
            } catch (Exception ignore) {
                continue;
            }
            String[] lines = content.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                int lineNo = i + 1;

                if (BACKDROP.matcher(line).find()) {
                    result.addHit(P_BACKDROP, rel, lineNo, line);
                }
                if (GRADIENT.matcher(line).find()) {
                    result.addHit(P_GRADIENT, rel, lineNo, line);
                }
                Matcher sm = SCALE.matcher(line);
                while (sm.find()) {
                    if (parseDoubleSafe(sm.group(1)) > 1.05) {
                        result.addHit(P_SCALE, rel, lineNo, line);
                        break;
                    }
                }

                // 全局样式文件（token/global 定义）天然包含 px 与颜色值，不计入硬编码命中
                if (!styleOwned) {
                    Matcher pxm = HARDCODED_PX.matcher(line);
                    while (pxm.find()) {
                        int v = parseIntSafe(pxm.group(1));
                        if (v > 1) {
                            result.addHit(P_HARDCODED_PX, rel, lineNo, line);
                            break;
                        }
                    }
                    if (HARDCODED_COLOR.matcher(line).find()) {
                        result.addHit(P_HARDCODED_COLOR, rel, lineNo, line);
                    }
                }

                // 悬空变量：引用了不在 tokenGroups 清单内的 var(--xxx)，variables.css 自身是定义处不算引用
                if (!knownVars.isEmpty() && !variablesCss) {
                    Matcher vm = VAR_REF.matcher(line);
                    while (vm.find()) {
                        String var = vm.group(1);
                        if (!knownVars.contains(var)) {
                            result.addHit(P_DANGLING_VAR, rel, lineNo, line);
                            result.danglingVars.add(var);
                        }
                    }
                }
            }
        }

        // 入口文件全局样式 import 校验
        checkEntryImports(root, result);

        return result;
    }

    private void checkEntryImports(Path root, LintResult result) {
        File mainJs = root.resolve("src/main.js").toFile();
        if (!FileUtil.exist(mainJs)) {
            mainJs = root.resolve("src/main.ts").toFile();
        }
        if (!FileUtil.exist(mainJs)) {
            // 入口文件尚未生成，交由 CriticAgent 的 P0 必备文件检查处理，这里不重复判定
            return;
        }
        String content;
        try {
            content = FileUtil.readString(mainJs, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return;
        }
        result.entryImportsVariables = content.contains("styles/variables.css");
        result.entryImportsGlobal = content.contains("styles/global.css");
        if (!result.entryImportsVariables) {
            result.addHit(P_MISSING_IMPORT, "src/main.js", 0, "缺少 import './styles/variables.css'");
        }
        if (!result.entryImportsGlobal) {
            result.addHit(P_MISSING_IMPORT, "src/main.js", 0, "缺少 import './styles/global.css'");
        }
    }

    private Set<String> collectKnownVars(DesignSpec spec) {
        Set<String> vars = new LinkedHashSet<>();
        if (spec == null || spec.getTokenGroups() == null) {
            return vars;
        }
        for (Map<String, String> group : spec.getTokenGroups().values()) {
            if (group != null) {
                vars.addAll(group.keySet());
            }
        }
        return vars;
    }

    private double parseDoubleSafe(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private Path projectRoot(Long appId) {
        return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId).toAbsolutePath().normalize();
    }

    /**
     * 单条命中记录。
     */
    @Getter
    public static class Hit {
        private final String pattern;
        private final String file;
        private final int line;
        private final String snippet;

        public Hit(String pattern, String file, int line, String snippet) {
            this.pattern = pattern;
            this.file = file;
            this.line = line;
            this.snippet = snippet;
        }
    }

    /**
     * 扫描结果：命中清单 + 各类命中计数 + 附带校验结论。
     */
    @Getter
    public static class LintResult {
        private final List<Hit> hits = new ArrayList<>();
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private final Set<String> danglingVars = new LinkedHashSet<>();
        private boolean entryImportsVariables = true;
        private boolean entryImportsGlobal = true;

        void addHit(String pattern, String file, int line, String snippet) {
            String snip = snippet == null ? "" : snippet.trim();
            if (snip.length() > 160) {
                snip = snip.substring(0, 160);
            }
            hits.add(new Hit(pattern, file, line, snip));
            counts.merge(pattern, 1, Integer::sum);
        }

        public int totalHits() {
            return hits.size();
        }

        /** 是否存在任何反模式命中（验收标准：为 0 视为通过）。 */
        public boolean hasAnyHit() {
            return !hits.isEmpty();
        }

        /**
         * 是否存在 P0 级硬伤：缺少全局样式 import 或悬空变量。
         * 这两类会直接导致样式不生效或变量失效，应强制 passed=false。
         */
        public boolean hasP0() {
            return !entryImportsVariables || !entryImportsGlobal || !danglingVars.isEmpty();
        }
    }
}
