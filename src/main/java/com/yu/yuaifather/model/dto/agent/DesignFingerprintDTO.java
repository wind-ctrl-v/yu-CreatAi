package com.yu.yuaifather.model.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 六层设计指纹 DTO（阶段二「后验证据」运行时 / 投影用）。
 * <p>
 * 由 {@code DesignExtractorAgent} 对精选页面摘要按六层契约提炼得到，经 {@code FingerprintParser}
 * 校验 / 规整后入库为 {@link com.yu.yuaifather.model.entity.DesignFingerprint}，
 * 并由 {@code FingerprintProjector} 投影为 {@link DesignSpec} 供运行时注入。
 *
 * <p>命名说明（评审 #8）：DTO 定名 {@code DesignFingerprintDTO}，持久化实体保持
 * {@code DesignFingerprint}，避免同名仅包不同导致的误 import。
 *
 * <h3>六层结构</h3>
 * <pre>
 *  ① tokens        设计 Token 层：配色比例 / 字号阶梯 / 圆角 / 阴影
 *  ② layout        布局骨架层：区块顺序 / 留白 / 视觉重心
 *  ③ components    组件模式层：导航 / 卡片 / CTA / 表单的惯用结构
 *  ④ interaction   交互动效层：hover / 过渡 / 动效节奏
 *  ⑤ antiPatterns  反模式层（负样本）：它刻意没做什么
 *  ⑥ styleProfile + tone  风格归类层
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DesignFingerprintDTO {

    /** schema 版本，例如 v1。读时按版本路由（见计划 2.2.1）。 */
    private String schemaVersion;

    /** 风格画像：startup / enterprise / ecommerce / portfolio / dashboard / landing / default。 */
    private String styleProfile;

    /** 来源类型：固定 EXTRACTED_FINGERPRINT。 */
    private String sourceType;

    /** 来源标识：页面 ID 或抓取批次号。 */
    private String sourceId;

    /** 置信度 0~1，由 FingerprintParser 按客观信号计算（不由 LLM 自报，见 1.6.0）。 */
    private Double confidence;

    /** ① 设计 Token 层。 */
    private Tokens tokens;

    /** ② 布局骨架层。 */
    private Layout layout;

    /** ③ 组件模式层：可复用组件结构关键词（不含业务文案）。 */
    private List<String> components;

    /** ④ 交互动效层。 */
    private Interaction interaction;

    /** ⑤ 反模式层（负样本）：刻意避开的写法。 */
    private List<String> antiPatterns;

    /** ⑥ 调性关键词：极简 / 科技 / 温暖等。 */
    private List<String> tone;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Tokens {
        /** 配色比例关系，如 "中性为主, 主色<10%"。 */
        private String colorRatio;
        /** 字号阶梯，如 ["32/24/16"]。 */
        private List<String> fontScale;
        /** 圆角强度，如 "8px 统一圆角"。 */
        private String radii;
        /** 阴影层级，如 "单层轻阴影"。 */
        private String shadow;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Layout {
        /** 区块骨架，如 "Hero + 3栏特性 + 定价"。 */
        private String skeleton;
        /** 留白节奏，如 "宽松"。 */
        private String whitespace;
        /** 视觉重心，如 "中间定价卡"。 */
        private String focus;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Interaction {
        /** 动效克制 or 浮夸，如 "克制"。 */
        private String motion;
        /** 过渡时长 / 曲线，如 "200ms ease"。 */
        private String transition;
    }
}
