package com.yu.yuaifather.agent.codegen.fileops;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具管理器
 * 统一管理所有工具，提供根据名称获取工具的功能
 */
@Slf4j
@Component
public class ToolManager {

    /**
     * 工具名称到工具实例的映射
     */
    private final Map<String, BaseTool> toolMap = new HashMap<>();

    /**
     * 自动注入所有工具
     */
    @Resource
    private BaseTool[] tools;

    /**
     * 初始化工具映射
 * 该方法在Bean实例化后自动执行，用于初始化工具管理器中的工具映射关系
 * 使用@PostConstruct注解确保在依赖注入完成后执行
     */
    @PostConstruct
    public void initTools() {
    // 遍历工具列表，将每个工具注册到工具映射中
        for (BaseTool tool : tools) {
        // 以工具名称为键，工具对象为值存入Map
            toolMap.put(tool.getToolName(), tool);
        // 记录工具注册日志，包含工具名称和显示名称
            log.info("注册工具: {} -> {}", tool.getToolName(), tool.getDisplayName());
        }
    // 记录工具管理器初始化完成日志，显示已注册工具总数
        log.info("工具管理器初始化完成，共注册 {} 个工具", toolMap.size());
    }

    /**
     * 根据工具名称获取工具实例
     *
     * @param toolName 工具英文名称
     * @return 工具实例
     */
    public BaseTool getTool(String toolName) {
        return toolMap.get(toolName);
    }

    /**
     * 获取已注册的工具集合
     *
     * @return 工具实例集合
     */
    public BaseTool[] getAllTools() {
        return tools;
    }
}
