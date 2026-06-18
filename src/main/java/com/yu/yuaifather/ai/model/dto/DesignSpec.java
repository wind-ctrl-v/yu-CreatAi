package com.yu.yuaifather.ai.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 设计规范（DesignSpec）——阶段一「先验规则」的结构化数据契约。
 * <p>
 * 由 {@code DesignTokenAgent} 根据 {@code GenerationPlan.styleProfile} 从
 * {@code skill/designs/*.md} 解析得到，写入 {@link com.yu.yuaifather.agent.core.Blackboard}，
 * 供 StyleAgent / CoderAgent 在并发分支中消费。
 *
 * <h3>核心契约：tokenGroups</h3>
 * <p>
 * {@link #tokenGroups} 是并发分支之间「确切变量名 → 值」的<b>唯一权威映射</b>：
 * <ul>
 *   <li>StyleAgent 生成 {@code variables.css} 时必须逐一输出其中的确切 {@code --xxx} 变量名，不得改名增删；</li>
 *   <li>CoderAgent 组件样式只能引用其中出现的变量名，不得自创；</li>
 *   <li>由此从源头消除「Coder 引用了 Style 未定义的变量」的悬空变量问题。</li>
 * </ul>
 *
 * <p>注意：{@link #fullContent} 仅用于审计 / 调试 / 离线排查，<b>禁止</b>原样注入 prompt；
 * 运行时注入必须使用结构化字段（tokenGroups / *Patterns）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DesignSpec {

    /** schema 版本，例如 v1。 */
    private String schemaVersion;

    /** 风格画像：startup / enterprise / ecommerce / portfolio / dashboard / landing / default。 */
    private String styleProfile;

    /** 来源类型：STATIC_MD / EXTRACTED_FINGERPRINT / RETRIEVED / DEFAULT。 */
    private String sourceType;

    /** 来源标识：md 路径、fingerprintId 或 evidenceId。 */
    private String sourceId;

    /** 仅静态规范使用的 md 路径，可选。 */
    private String sourceMd;

    /** 完整 Markdown 原文，仅审计使用，不直接注入 prompt。 */
    private String fullContent;

    /** 置信度 0~1，用于阶段二/三排序；静态规范恒为 1.0。 */
    private Double confidence;

    /** 优先级，越高越优先；静态规范在 md 中人工标注，缺省给基线值 50。 */
    private Integer priority;

    /** 调性关键词：极简、科技、温暖等。 */
    private List<String> tone;

    /**
     * 设计 Token 分组：{@code Map<groupName, Map<varName, value>>}。
     * <p>例如 {@code {"color": {"--color-primary-500": "#2563eb", ...}, "space": {...}}}。
     * 这是并发分支唯一权威的变量名契约（见类注释）。
     */
    private Map<String, Map<String, String>> tokenGroups;

    /** 布局模式清单。 */
    private List<String> layoutPatterns;

    /** 推荐写法清单。 */
    private List<String> preferredPatterns;

    /** 交互模式清单。 */
    private List<String> interactionPatterns;

    /** 禁用模式清单（违反视为严重错误）。 */
    private List<String> forbiddenPatterns;
}
