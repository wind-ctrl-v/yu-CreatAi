package com.yu.yuaifather.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 指纹治理状态机（阶段二，见计划 2.5）。
 * <pre>
 *  CANDIDATE → APPROVED   人工/规则审核通过，参与召回
 *  CANDIDATE → REJECTED   低质量/重复/含风险，不参与召回
 *  APPROVED  → ARCHIVED   过期或被更优样本替代，不参与召回
 * </pre>
 * 仅 {@link #APPROVED} 参与运行时召回。
 */
@Getter
public enum FingerprintStatus {

    /** 新提炼结果，schema 已通过但未审核，不参与召回。 */
    CANDIDATE("候选", "CANDIDATE"),
    /** 审核通过，参与召回。 */
    APPROVED("已通过", "APPROVED"),
    /** 低质量 / 重复 / 含风险，不参与召回。 */
    REJECTED("已拒绝", "REJECTED"),
    /** 过期或被更优样本替代，不参与召回。 */
    ARCHIVED("已归档", "ARCHIVED");

    private final String text;
    private final String value;

    FingerprintStatus(String text, String value) {
        this.text = text;
        this.value = value;
    }

    public static FingerprintStatus getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (FingerprintStatus anEnum : FingerprintStatus.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
