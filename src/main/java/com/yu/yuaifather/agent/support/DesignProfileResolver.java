package com.yu.yuaifather.agent.support;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.ai.model.dto.DesignSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DesignProfileResolver：阶段一「先验规则」的零 LLM 解析器。
 * <p>
 * 职责：
 * <ol>
 *   <li>把 ArchitectAgent 识别出的 {@code styleProfile} + 置信度，按白名单与阈值归一化为合法 profile
 *       （未知 / 低置信 → {@code default}，见计划 1.2.1）；</li>
 *   <li>从 classpath 固定白名单文件 {@code skill/designs/{profile}.md} 读取并解析为结构化 {@link DesignSpec}；</li>
 *   <li>读取失败时回退到内置默认规范，保证 tokenGroups 强契约始终存在。</li>
 * </ol>
 *
 * <h3>安全</h3>
 * 仅从 classpath 读取白名单内的固定文件名，profile 经枚举校验，<b>无</b>用户输入拼接路径，无路径穿越风险。
 */
@Slf4j
@Component
public class DesignProfileResolver {

    /** 兜底 profile。 */
    public static final String DEFAULT_PROFILE = "default";

    /** 合法 profile 白名单（与 skill/designs/*.md 一一对应）。 */
    private static final Set<String> PROFILE_WHITELIST = Set.of(
            "startup", "enterprise", "ecommerce", "portfolio", "dashboard", "landing", DEFAULT_PROFILE);

    /** styleProfile 置信度阈值：低于此值一律归 default。 */
    private static final double CONFIDENCE_THRESHOLD = 0.6;

    /** 静态规范缺省优先级基线（md 未显式标注 priority 时使用）。 */
    private static final int DEFAULT_PRIORITY = 50;

    private static final Pattern PRIORITY_PATTERN = Pattern.compile("(?m)^\\s*priority\\s*[:：]\\s*(\\d+)\\s*$");
    private static final Pattern CSS_BLOCK_PATTERN = Pattern.compile("```css\\s*([\\s\\S]*?)```");
    private static final Pattern CSS_VAR_PATTERN = Pattern.compile("(--[A-Za-z0-9-]+)\\s*:\\s*([^;]+);");

    /**
     * 把识别出的 profile + 置信度归一化为合法 profile。
     *
     * @param rawProfile ArchitectAgent 识别的原始 profile（可空）
     * @param confidence 识别置信度（可空，缺省视为低置信）
     * @return 合法 profile；未知 / 低置信 / 为空时返回 {@link #DEFAULT_PROFILE}
     */
    public String normalizeProfile(String rawProfile, Double confidence) {
        if (StrUtil.isBlank(rawProfile)) {
            return DEFAULT_PROFILE;
        }
        String p = rawProfile.trim().toLowerCase();
        if (!PROFILE_WHITELIST.contains(p)) {
            log.info("styleProfile 不在白名单，归 default：raw={}", rawProfile);
            return DEFAULT_PROFILE;
        }
        if (confidence == null || confidence < CONFIDENCE_THRESHOLD) {
            log.info("styleProfile 置信度过低（{}<{}），归 default：raw={}", confidence, CONFIDENCE_THRESHOLD, rawProfile);
            return DEFAULT_PROFILE;
        }
        return p;
    }

    /**
     * 加载指定 profile 的设计规范。读取或解析失败时回退到内置默认规范，绝不抛异常。
     *
     * @param profile 已归一化的合法 profile
     * @return 结构化 DesignSpec（sourceType=STATIC_MD 或 DEFAULT）
     */
    public DesignSpec loadDesignSpec(String profile) {
        String p = PROFILE_WHITELIST.contains(profile) ? profile : DEFAULT_PROFILE;
        String resourcePath = "skill/designs/" + p + ".md";
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                log.warn("设计规范文件不存在，回退内置默认规范：{}", resourcePath);
                return buildBuiltinDefaultSpec();
            }
            String content;
            try (InputStream is = resource.getInputStream()) {
                content = IoUtil.read(is, StandardCharsets.UTF_8);
            }
            DesignSpec spec = parse(content, p, resourcePath);
            // 解析后若 tokenGroups 为空，说明 md 格式异常，回退内置默认，保证强契约存在
            if (spec.getTokenGroups() == null || spec.getTokenGroups().isEmpty()) {
                log.warn("设计规范解析后 tokenGroups 为空，回退内置默认规范：{}", resourcePath);
                return buildBuiltinDefaultSpec();
            }
            return spec;
        } catch (Exception e) {
            log.error("加载设计规范失败，回退内置默认规范：{} msg={}", resourcePath, e.getMessage());
            return buildBuiltinDefaultSpec();
        }
    }

    /**
     * 解析 md 文本为 DesignSpec。约定格式见 skill/designs/*.md：
     * <ul>
     *   <li>{@code priority: N} 行</li>
     *   <li>{@code ## tokens} 下的 ```css ``` 块定义 {@code --var: value;}</li>
     *   <li>{@code ## tone / layout / preferred / forbidden / interaction} 下的 {@code - } 列表</li>
     * </ul>
     */
    private DesignSpec parse(String content, String profile, String resourcePath) {
        // priority
        int priority = DEFAULT_PRIORITY;
        Matcher pm = PRIORITY_PATTERN.matcher(content);
        if (pm.find()) {
            priority = Integer.parseInt(pm.group(1));
        }

        // tokenGroups（来自 ```css 块）
        Map<String, Map<String, String>> tokenGroups = new LinkedHashMap<>();
        Matcher cssm = CSS_BLOCK_PATTERN.matcher(content);
        if (cssm.find()) {
            Matcher varm = CSS_VAR_PATTERN.matcher(cssm.group(1));
            while (varm.find()) {
                String varName = varm.group(1).trim();
                String value = varm.group(2).trim();
                String group = groupOf(varName);
                tokenGroups.computeIfAbsent(group, k -> new LinkedHashMap<>()).put(varName, value);
            }
        }

        // 列表型小节
        Map<String, List<String>> sections = parseListSections(content);

        return DesignSpec.builder()
                .schemaVersion("v1")
                .styleProfile(profile)
                .sourceType("STATIC_MD")
                .sourceId(resourcePath)
                .sourceMd(resourcePath)
                .fullContent(content)
                .confidence(1.0)
                .priority(priority)
                .tone(sections.getOrDefault("tone", new ArrayList<>()))
                .tokenGroups(tokenGroups)
                .layoutPatterns(sections.getOrDefault("layout", new ArrayList<>()))
                .preferredPatterns(sections.getOrDefault("preferred", new ArrayList<>()))
                .interactionPatterns(sections.getOrDefault("interaction", new ArrayList<>()))
                .forbiddenPatterns(sections.getOrDefault("forbidden", new ArrayList<>()))
                .build();
    }

    /**
     * 按 {@code ## 小节} 收集 {@code - } 列表项。fenced code block 内的内容忽略。
     */
    private Map<String, List<String>> parseListSections(String content) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        String current = null;
        boolean inFence = false;
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            if (trimmed.startsWith("## ")) {
                current = mapSectionKey(trimmed.substring(3).trim().toLowerCase());
                continue;
            }
            if (current != null && (trimmed.startsWith("- ") || trimmed.startsWith("* "))) {
                String item = trimmed.substring(2).trim();
                if (StrUtil.isNotBlank(item)) {
                    result.computeIfAbsent(current, k -> new ArrayList<>()).add(item);
                }
            }
        }
        return result;
    }

    /** 把小节标题（中英文）映射到固定字段键。 */
    private String mapSectionKey(String heading) {
        if (heading.contains("tone") || heading.contains("调性")) return "tone";
        if (heading.contains("layout") || heading.contains("布局")) return "layout";
        if (heading.contains("preferred") || heading.contains("推荐")) return "preferred";
        if (heading.contains("forbidden") || heading.contains("禁用")) return "forbidden";
        if (heading.contains("interaction") || heading.contains("交互")) return "interaction";
        return null;
    }

    /** 变量名分组：取 {@code --} 之后到首个 {@code -} 之前的段，如 --color-primary-500 → color。 */
    private String groupOf(String varName) {
        String name = varName.startsWith("--") ? varName.substring(2) : varName;
        int idx = name.indexOf('-');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    /**
     * 内置默认规范：在 default.md 也读取失败时使用，保证 tokenGroups 强契约始终存在。
     * 变量名集合必须与 skill/designs/*.md 保持一致（这是并发分支的权威契约）。
     */
    public DesignSpec buildBuiltinDefaultSpec() {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();

        Map<String, String> color = new LinkedHashMap<>();
        color.put("--color-primary-50", "#eef2ff");
        color.put("--color-primary-500", "#4f46e5");
        color.put("--color-primary-600", "#4338ca");
        color.put("--color-secondary-500", "#0ea5e9");
        color.put("--color-bg", "#ffffff");
        color.put("--color-surface", "#f8fafc");
        color.put("--color-text", "#0f172a");
        color.put("--color-text-muted", "#64748b");
        color.put("--color-border", "#e2e8f0");
        groups.put("color", color);

        Map<String, String> font = new LinkedHashMap<>();
        font.put("--font-family-base", "-apple-system, BlinkMacSystemFont, \"PingFang SC\", \"Microsoft YaHei\", sans-serif");
        font.put("--font-size-xs", "12px");
        font.put("--font-size-sm", "14px");
        font.put("--font-size-base", "16px");
        font.put("--font-size-lg", "18px");
        font.put("--font-size-xl", "22px");
        font.put("--font-size-2xl", "28px");
        font.put("--font-size-3xl", "36px");
        groups.put("font", font);

        Map<String, String> leading = new LinkedHashMap<>();
        leading.put("--leading-tight", "1.25");
        leading.put("--leading-normal", "1.5");
        leading.put("--leading-relaxed", "1.75");
        groups.put("leading", leading);

        Map<String, String> tracking = new LinkedHashMap<>();
        tracking.put("--tracking-tight", "-0.01em");
        tracking.put("--tracking-normal", "0");
        tracking.put("--tracking-wide", "0.02em");
        groups.put("tracking", tracking);

        Map<String, String> space = new LinkedHashMap<>();
        space.put("--space-1", "4px");
        space.put("--space-2", "8px");
        space.put("--space-3", "12px");
        space.put("--space-4", "16px");
        space.put("--space-6", "24px");
        space.put("--space-8", "32px");
        space.put("--space-12", "48px");
        groups.put("space", space);

        Map<String, String> radius = new LinkedHashMap<>();
        radius.put("--radius-sm", "4px");
        radius.put("--radius-md", "8px");
        radius.put("--radius-lg", "16px");
        radius.put("--radius-full", "9999px");
        groups.put("radius", radius);

        Map<String, String> shadow = new LinkedHashMap<>();
        shadow.put("--shadow-sm", "0 1px 2px rgba(15,23,42,.06)");
        shadow.put("--shadow-md", "0 4px 12px rgba(15,23,42,.08)");
        groups.put("shadow", shadow);

        return DesignSpec.builder()
                .schemaVersion("v1")
                .styleProfile(DEFAULT_PROFILE)
                .sourceType("DEFAULT")
                .sourceId("builtin:default")
                .confidence(1.0)
                .priority(DEFAULT_PRIORITY)
                .tone(List.of("克制", "中性", "通用"))
                .tokenGroups(groups)
                .layoutPatterns(List.of(
                        "顶部固定导航 + 主内容区 + 页脚的经典三段式",
                        "12 栅格弹性布局，区块之间用 --space-8/--space-12 拉开节奏",
                        "充足留白，单屏聚焦一个主要信息层级"))
                .preferredPatterns(List.of(
                        "所有颜色/字号/间距/圆角/阴影一律引用 var(--xxx) token",
                        "卡片用 1px --color-border 描边 + --shadow-sm 轻阴影区分层级",
                        "正文行高用 --leading-normal，标题用 --leading-tight"))
                .interactionPatterns(List.of(
                        "hover 仅做轻微颜色/阴影变化，过渡 150-200ms ease",
                        "避免大幅缩放与位移，交互克制"))
                .forbiddenPatterns(List.of(
                        "禁止 backdrop-filter 毛玻璃效果",
                        "禁止纯装饰性的 linear-gradient / radial-gradient 背景",
                        "禁止硬编码字号/间距/圆角 px（1px 边框除外），必须用 token",
                        "禁止硬编码十六进制/rgb 颜色，必须用 var(--color-*)",
                        "禁止 hover 时 transform: scale() 超过 1.05 的浮夸放大"))
                .build();
    }
}
