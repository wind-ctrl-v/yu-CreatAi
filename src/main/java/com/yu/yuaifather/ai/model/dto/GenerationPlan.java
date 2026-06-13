package com.yu.yuaifather.ai.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * ArchitectAgent 输出的生成计划，供 CoderAgent / StyleAgent / ImageAgent 消费。
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
