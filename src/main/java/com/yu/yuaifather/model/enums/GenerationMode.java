package com.yu.yuaifather.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * V2 生成模式。
 * <p>三种模式按成本/质量递增排序，{@link #rank} 用于"套餐封顶"比较：
 * 服务端最终模式不得超过用户套餐允许的最高模式。</p>
 */
@Getter
public enum GenerationMode {

    /** 低成本：单遍生成、精简 evidence、不启用 LLM Critic。 */
    LOW_COST("低成本", "low_cost", 1),
    /** 标准：默认模式，可选 Coder∥Style，规则失败才触发 1 轮 LLM Critic。 */
    STANDARD("标准", "standard", 2),
    /** 高质量：Coder∥Style 并发 + 多轮 Critic。 */
    HIGH_QUALITY("高质量", "high_quality", 3);

    private final String text;
    private final String value;
    /** 等级，数值越大成本/质量越高，用于封顶比较。 */
    private final int rank;

    GenerationMode(String text, String value, int rank) {
        this.text = text;
        this.value = value;
        this.rank = rank;
    }

    /**
     * 根据 value 获取枚举，无法识别返回 {@code null}。
     */
    public static GenerationMode getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (GenerationMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return null;
    }

    /**
     * 取两个模式中等级较低者（用于封顶：意图与允许上限取小）。
     */
    public static GenerationMode min(GenerationMode a, GenerationMode b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.rank <= b.rank ? a : b;
    }
}
