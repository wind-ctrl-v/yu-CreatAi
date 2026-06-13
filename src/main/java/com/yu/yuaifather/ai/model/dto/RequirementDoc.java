package com.yu.yuaifather.ai.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * ProductAgent 输出的需求文档结构（与 prompt 契约严格一致）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequirementDoc {

    /** 项目名称（中文）。 */
    private String projectName;

    /** 项目摘要（一句话描述用户最终能用它做什么）。 */
    private String summary;

    /** 目标用户群体描述。 */
    private String targetUser;

    /** 核心功能清单（按优先级排序）。 */
    private List<String> coreFeatures;

    /** 页面规划（每个页面一项，含 path/name/purpose）。 */
    private List<PageSpec> pages;

    /** 主题色配置（如 primary/secondary/background/text 等键）。 */
    private Map<String, String> colorTheme;

    /** 图片需求清单（待后续 ArchitectAgent 进一步落到 imageSlots）。 */
    private List<ImageNeed> imageNeeds;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageSpec {
        private String path;
        private String name;
        private String purpose;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageNeed {
        /** 槽位 ID，命名规范：img_{位置}_{序号}，例如 img_hero_1。 */
        private String slotId;
        /** 用途描述（中文）。 */
        private String usage;
        /** 推荐的英文搜索关键词。 */
        private String keyword;
    }
}
