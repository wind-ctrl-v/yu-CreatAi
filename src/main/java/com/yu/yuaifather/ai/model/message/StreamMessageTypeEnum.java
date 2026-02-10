package com.yu.yuaifather.ai.model.message;

import lombok.Getter;

/**
 * 流式消息类型枚举
 */
@Getter
public enum StreamMessageTypeEnum {

    AI_RESPONSE("ai_response", "AI响应"),
    TOOL_REQUEST("tool_request", "工具请求"),
    TOOL_EXECUTED("tool_executed", "工具执行结果");

    private final String value;
    private final String text;

/**
 * 枚举类型的构造方法
 * @param value 枚举值，用于标识枚举的唯一值
 * @param text 枚举显示文本，用于在前端展示或描述枚举的含义
 */
    StreamMessageTypeEnum(String value, String text) {

    // 为枚举实例赋值
        this.value = value;  // 设置枚举的值
        this.text = text;    // 设置枚举的显示文本
    }

    /**
     * 根据值获取枚举
     */
    public static StreamMessageTypeEnum getEnumByValue(String value) {
        for (StreamMessageTypeEnum typeEnum : values()) {
            if (typeEnum.getValue().equals(value)) {
                return typeEnum;
            }
        }
        return null;
    }
}
