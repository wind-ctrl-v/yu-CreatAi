package com.yu.yuaifather.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 用户设计行为事件类型枚举（阶段一：用户偏好闭环）。
 *
 * @author yu
 */
@Getter
public enum EventTypeEnum {

    PAGE_GENERATED("page_generated", "页面生成"),
    PAGE_REGENERATED("page_regenerated", "页面重新生成"),
    PAGE_SAVED("page_saved", "页面保存"),
    PAGE_EXPORTED("page_exported", "页面导出"),
    PAGE_DELETED("page_deleted", "页面删除"),
    COLOR_CHANGED("color_changed", "颜色修改"),
    LAYOUT_CHANGED("layout_changed", "布局修改"),
    COMPONENT_ADDED("component_added", "组件添加"),
    COMPONENT_REMOVED("component_removed", "组件移除"),
    FEEDBACK_SUBMITTED("feedback_submitted", "反馈提交"),

    // ===== 负反馈事件（P0-残留-1 补全） =====
    /** 用户在编辑器内撤销修改（弱负信号 -0.05） */
    UNDO("undo", "撤销修改"),
    /** 用户回退到 AI 原始版本（强负信号 -0.20） */
    REVERT_TO_GENERATED("revert_to_generated", "回退到AI版本"),
    /** 生成未完成时用户主动关闭（中负信号 -0.10） */
    ABANDON("abandon", "中途放弃生成"),
    /** 用户拒绝 AI 推荐（中负信号 -0.10） */
    REJECT_SUGGESTION("reject_suggestion", "拒绝AI推荐"),
    /** 用户显式点踩（强负信号 -0.20） */
    EXPLICIT_DISLIKE("explicit_dislike", "显式点踩");

    private final String value;
    private final String text;

    EventTypeEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    public static EventTypeEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (EventTypeEnum anEnum : EventTypeEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
