package com.yu.yuaifather.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 用户套餐类型。决定可用的默认模式与最高模式（封顶）。
 */
@Getter
public enum PlanType {

    FREE("免费用户"),
    PRO("普通付费用户"),
    PREMIUM("高级会员"),
    ENTERPRISE("企业用户"),
    ADMIN("管理员");

    private final String text;

    PlanType(String text) {
        this.text = text;
    }

    /**
     * 根据名称获取枚举（忽略大小写），无法识别返回 {@link #FREE} 兜底。
     */
    public static PlanType fromName(String name) {
        if (ObjUtil.isEmpty(name)) {
            return FREE;
        }
        for (PlanType plan : values()) {
            if (plan.name().equalsIgnoreCase(name)) {
                return plan;
            }
        }
        return FREE;
    }
}
