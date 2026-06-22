package com.yu.yuaifather.tool.system;

import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.ai.model.dto.*;
import com.yu.yuaifather.tool.core.SystemTool;
import com.yu.yuaifather.tool.core.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ContextCompressTool：上下文压缩器（System Tool，零 LLM）。
 * <p>
 * 将 DesignSpec / UserDesignContext / ProjectBrandContext / DesignEvidence / ImageMap
 * 压缩为对应 Agent 的 PromptContext，控制 Chat Token 消耗。
 * <p>
 * 通过配置控制截断长度：
 * <ul>
 *   <li>{@code context.max-evidence-chars: 180} — 每条证据最大字符数</li>
 *   <li>{@code context.max-user-preference-chars: 300} — 用户偏好最大字符数</li>
 *   <li>{@code context.max-project-profile-chars: 300} — 项目品牌最大字符数</li>
 *   <li>{@code context.max-design-spec-chars: 1500} — 设计规范最大字符数</li>
 * </ul>
 */
@Slf4j
@Component
public class ContextCompressTool implements SystemTool<ContextCompressTool.CompressInput, PromptContext> {

    @Value("${context.max-evidence-count:3}")
    private int maxEvidenceCount;

    @Value("${context.max-evidence-chars:180}")
    private int maxEvidenceChars;

    @Value("${context.max-user-preference-chars:300}")
    private int maxUserPrefChars;

    @Value("${context.max-project-profile-chars:300}")
    private int maxProjectProfileChars;

    @Value("${context.max-design-spec-chars:1500}")
    private int maxDesignSpecChars;

    public static final String TOOL_NAME = "ContextCompressTool";

    /** 压缩输入。 */
    public record CompressInput(
            DesignSpec designSpec,
            UserDesignContext userContext,
            ProjectBrandContext projectContext,
            DesignEvidence designEvidence,
            Map<String, String> imageMap,
            String targetAgent  // "PageGenerateAgent" / "CoderAgent" / "StyleAgent" / "CriticAgent"
    ) {}

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public PromptContext execute(CompressInput input, ToolContext context) {
        PromptContext.PromptContextBuilder builder = PromptContext.builder();

        // 1. 压缩设计规范
        if (input.designSpec() != null) {
            builder.styleProfile(input.designSpec().getStyleProfile());
            builder.designSpecSummary(truncate(buildDesignSpecSummary(input.designSpec()), maxDesignSpecChars));
        }

        // 2. 压缩用户偏好
        if (input.userContext() != null && input.userContext().hasPreferences()) {
            builder.userPreferenceSummary(truncate(buildUserPrefSummary(input.userContext()), maxUserPrefChars));
        }

        // 3. 压缩项目品牌
        if (input.projectContext() != null && input.projectContext().hasBrandProfile()) {
            builder.projectBrandSummary(truncate(buildBrandSummary(input.projectContext()), maxProjectProfileChars));
        }

        // 4. 压缩设计证据
        if (input.designEvidence() != null && !input.designEvidence().isEmpty()) {
            List<DesignEvidence.Item> items = input.designEvidence().getItems();
            StringBuilder evidenceText = new StringBuilder();
            int count = 0;
            for (DesignEvidence.Item item : items) {
                if (count >= maxEvidenceCount) break;
                String text = buildEvidenceItem(item);
                if (text != null && !text.isBlank()) {
                    evidenceText.append(truncate(text, maxEvidenceChars)).append("\n");
                    count++;
                }
            }
            if (evidenceText.length() > 0) {
                builder.evidenceSummary(evidenceText.toString().trim());
            }
        }

        // 5. 图片映射
        if (input.imageMap() != null && !input.imageMap().isEmpty()) {
            builder.imageMap(input.imageMap());
        }

        // 6. 按目标 Agent 生成完整 Prompt 文本
        builder.targetAgent(input.targetAgent());
        builder.fullPrompt(buildFullPrompt(builder, input.targetAgent()));

        return builder.build();
    }

    // ---- 辅助方法 ----

    private String buildDesignSpecSummary(DesignSpec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("风格:").append(spec.getStyleProfile());
        if (spec.getTone() != null && !spec.getTone().isEmpty()) {
            sb.append(" 调性:").append(String.join(",", spec.getTone()));
        }
        if (spec.getForbiddenPatterns() != null && !spec.getForbiddenPatterns().isEmpty()) {
            sb.append(" 禁止:").append(String.join(",", spec.getForbiddenPatterns()));
        }
        if (spec.getPreferredPatterns() != null && !spec.getPreferredPatterns().isEmpty()) {
            sb.append(" 推荐:").append(String.join(",", spec.getPreferredPatterns()));
        }
        return sb.toString();
    }

    private String buildUserPrefSummary(UserDesignContext ctx) {
        StringBuilder sb = new StringBuilder();
        appendIfNotEmpty(sb, "偏好风格", ctx.getPreferredStyles());
        appendIfNotEmpty(sb, "偏好颜色", ctx.getPreferredColors());
        appendIfNotEmpty(sb, "偏好布局", ctx.getPreferredLayouts());
        appendIfNotEmpty(sb, "偏好组件", ctx.getPreferredComponents());
        appendIfNotEmpty(sb, "反感风格", ctx.getDislikedStyles());
        appendIfNotEmpty(sb, "反感颜色", ctx.getDislikedColors());
        return sb.toString();
    }

    private String buildBrandSummary(ProjectBrandContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(ctx.getBrandName())) sb.append("品牌:").append(ctx.getBrandName()).append(";");
        if (StrUtil.isNotBlank(ctx.getIndustry())) sb.append("行业:").append(ctx.getIndustry()).append(";");
        if (ctx.getBrandColors() != null && !ctx.getBrandColors().isEmpty()) {
            sb.append("品牌色:").append(String.join(",",
                    ctx.getBrandColors().values().stream().limit(5).toList())).append(";");
        }
        appendIfNotEmpty(sb, "品牌调性", ctx.getBrandTone());
        appendIfNotEmpty(sb, "禁用", ctx.getForbiddenRules());
        return sb.toString();
    }

    private String buildEvidenceItem(DesignEvidence.Item item) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(item.getSourceType()).append("]");
        if (item.getLayoutPatterns() != null && !item.getLayoutPatterns().isEmpty()) {
            sb.append(" 布局:").append(String.join(",", item.getLayoutPatterns()));
        }
        if (item.getPreferredPatterns() != null && !item.getPreferredPatterns().isEmpty()) {
            sb.append(" 推荐:").append(String.join(",", item.getPreferredPatterns()));
        }
        return sb.toString();
    }

    private void appendIfNotEmpty(StringBuilder sb, String label, java.util.List<String> list) {
        if (list != null && !list.isEmpty()) {
            sb.append(label).append(":").append(String.join(",", list)).append(";");
        }
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...";
    }

    private String buildFullPrompt(PromptContext.PromptContextBuilder builder, String targetAgent) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 设计规范\n").append(nullToEmpty(builder.build().getDesignSpecSummary())).append("\n\n");

        String userPref = builder.build().getUserPreferenceSummary();
        if (userPref != null && !userPref.isBlank()) {
            sb.append("## 用户偏好\n").append(userPref).append("\n\n");
        }

        String brand = builder.build().getProjectBrandSummary();
        if (brand != null && !brand.isBlank()) {
            sb.append("## 项目品牌\n").append(brand).append("\n\n");
        }

        String evidence = builder.build().getEvidenceSummary();
        if (evidence != null && !evidence.isBlank()) {
            sb.append("## 参考设计证据\n").append(evidence).append("\n\n");
        }

        return sb.toString();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
