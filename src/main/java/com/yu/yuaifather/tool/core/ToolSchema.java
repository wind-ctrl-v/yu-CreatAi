package com.yu.yuaifather.tool.core;

/**
 * Tool Schema —— Agent Tool 的元数据描述，供 LLM Function Calling 使用。
 * <p>
 * 包含工具名称、人类可读描述及参数定义（JSON Schema 格式）。
 */
public class ToolSchema {

    /** 工具名称（与 AgentTool.name() 一致）。 */
    private String name;

    /** 人类可读的工具描述，LLM 据此判断何时调用。 */
    private String description;

    /** 输入参数的 JSON Schema 定义。 */
    private String parametersJson;

    public static ToolSchema of(String name, String description, String parametersJson) {
        ToolSchema schema = new ToolSchema();
        schema.name = name;
        schema.description = description;
        schema.parametersJson = parametersJson;
        return schema;
    }

    /** 简化工厂：仅含名称和描述，参数通过反射推断。 */
    public static ToolSchema of(String description) {
        ToolSchema schema = new ToolSchema();
        schema.description = description;
        return schema;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getParametersJson() {
        return parametersJson;
    }
}
