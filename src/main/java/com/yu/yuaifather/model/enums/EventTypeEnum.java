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
    FEEDBACK_SUBMITTED("feedback_submitted", "反馈提交");

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
