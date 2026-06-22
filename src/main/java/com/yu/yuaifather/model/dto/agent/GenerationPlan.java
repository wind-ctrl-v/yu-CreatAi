package com.yu.yuaifather.model.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * ArchitectAgent 输出的生成计划，供 CoderAgent / StyleAgent / ImageSearchTool 消费。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationPlan {

    /** 技术栈标识：vue_project / html。 */
    private String techStack;

    /** 需求摘要（来自 ProductAgent）。 */
    private String requirementSummary;

    /**
     * 行业风格画像（阶段一新增，可选）：
     * startup / enterprise / ecommerce / portfolio / dashboard / landing / default。
     * <p>由 ArchitectAgent 从需求识别，供 DesignTokenTool 加载对应设计规范。
     * 识别失败 / 不在白名单 / 低置信时由 DesignTokenTool 归一为 {@code default}。
     * <p>向后兼容：旧 plan JSON 无此字段时反序列化为 null，不影响解析。
     */
    private String styleProfile;

    /**
     * styleProfile 的识别置信度（阶段一新增，可选，0~1）。
     * <p>基于关键词命中强度等客观信号，非 LLM 自由发挥；
     * 低于阈值（如 0.6）时 DesignTokenTool 一律归 {@code default}（见计划 1.2.1）。
     */
    private Double styleProfileConfidence;

    /** 待生成文件清单（建议 ≤ 28 个）。 */
    private List<String> fileStructure;

    /** 路由配置（仅 vue_project 使用）。 */
    private List<RouteSpec> routerConfig;

    /** 图片槽列表。 */
    private List<ImageSlot> imageSlots;

    /** 主题色配置。 */
    private Map<String, String> colorTheme;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteSpec {
        /** 路由路径，如 "/" "/detail"。 */
        private String path;
        /** 路由名称。 */
        private String name;
        /** 对应的页面组件相对路径，如 "src/pages/Home.vue"。 */
        private String component;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageSlot {
        /** 槽位 ID，命名规范：img_{位置}_{序号}。 */
        private String slotId;
        /** 英文搜索关键词。 */
        private String keyword;
        /** 风格："photo" 或 "illustration"。 */
        private String style;
        /** 使用位置描述（中文）。 */
        private String usedIn;
    }
}
