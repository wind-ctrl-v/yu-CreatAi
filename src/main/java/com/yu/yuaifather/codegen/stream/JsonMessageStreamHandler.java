package com.yu.yuaifather.codegen.stream;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.yu.yuaifather.codegen.stream.model.*;
import com.yu.yuaifather.codegen.tools.BaseTool;
import com.yu.yuaifather.codegen.tools.ToolManager;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.model.enums.ChatHistoryMessageTypeEnum;
import com.yu.yuaifather.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.Set;

/**
 * JSON 消息流处理器
 * 处理 VUE_PROJECT 类型的复杂流式响应，包含工具调用信息
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    @Resource
    private ToolManager toolManager;

    /**
     * 处理 TokenStream（VUE_PROJECT）
     * 解析 JSON 消息并重组为完整的响应格式
     *
     * @param originFlux         原始流
     * @param chatHistoryService 聊天历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @return 处理后的流
     */
    public Flux<String> handle(Flux<String> originFlux,
                               ChatHistoryService chatHistoryService,
                               long appId, User loginUser) {
        // 收集数据用于生成后端记忆格式
        StringBuilder chatHistoryStringBuilder = new StringBuilder();
        // 用于跟踪已经见过的工具ID，判断是否是第一次调用
        Set<String> seenToolIds = new HashSet<>();
        return originFlux
                .map(chunk -> {
                    // 解析每个 JSON 消息块
                    return handleJsonMessageChunk(chunk, chatHistoryStringBuilder, seenToolIds);
                })
                .filter(StrUtil::isNotEmpty) // 过滤空字串
                .doOnComplete(() -> {
                    // 流式响应完成后，添加 AI 消息到对话历史
                    String aiResponse = chatHistoryStringBuilder.toString();
                    chatHistoryService.addChatMessage(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                })
                .doOnError(error -> {
                    // 错误消息不写入对话历史，避免污染后续上下文；仅落日志，前端通过 SSE 错误事件感知
                    log.error("AI 回复失败 appId={} err={}", appId, error.getMessage(), error);
                });
    }

    /**
     * 解析并收集 TokenStream 数据
     * 该方法处理不同类型的消息流，包括AI响应、工具请求和工具执行结果
     * @param chunk 接收到的消息块
     * @param chatHistoryStringBuilder 用于构建聊天历史记录的字符串构建器
     * @param seenToolIds 已处理的工具ID集合，用于避免重复处理
     * @return 返回处理后的消息内容，用于显示或进一步处理
     */
    private String handleJsonMessageChunk(String chunk, StringBuilder chatHistoryStringBuilder, Set<String> seenToolIds) {
        // 解析 JSON 消息块为 StreamMessage 对象（容错：解析失败时静默返回空）
        StreamMessage streamMessage;
        try {
            streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
        } catch (Exception e) {
            log.warn("StreamMessage 解析失败，丢弃本块: chunk_head={} err={}", StrUtil.subPre(chunk, 200), e.getMessage());
            return "";
        }
        // 根据消息类型获取对应的枚举值
        StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
        // 根据不同的消息类型进行相应的处理
        switch (typeEnum) {
            // 处理 AI 响应类型消息
            case AI_RESPONSE -> {
                // 将消息块解析为 AiResponseMessage 对象
                AiResponseMessage aiMessage = JSONUtil.toBean(chunk, AiResponseMessage.class);
                String data = aiMessage.getData();
                // 直接拼接响应
                chatHistoryStringBuilder.append(data);
                return data;
            }
            case TOOL_REQUEST -> {
                ToolRequestMessage toolRequestMessage = JSONUtil.toBean(chunk, ToolRequestMessage.class);
                String toolId = toolRequestMessage.getId();
                String toolName = toolRequestMessage.getName();
                // 检查是否是第一次看到这个工具 ID
                if (toolId != null && !seenToolIds.contains(toolId)) {
                    // 第一次调用这个工具，记录 ID 并完整返回工具信息
                    seenToolIds.add(toolId);
                    //根据名称获得工具实例
                    BaseTool tool = toolManager.getTool(toolName);
                    //返回格式化的工具调用信息
                    return tool.generateToolRequestResponse();
                } else {
                    // 不是第一次调用这个工具，直接返回空
                    return "";
                }
            }
            case TOOL_EXECUTED -> {
                ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(chunk, ToolExecutedMessage.class);
                String toolName = toolExecutedMessage.getName();
                JSONObject jsonObject = null;
                try {
                    jsonObject = JSONUtil.parseObj(toolExecutedMessage.getArguments());
                } catch (Exception e) {
                    log.warn("工具参数 JSON 解析失败，使用空对象降级: err={}", e.getMessage());
                }
                if (jsonObject == null) {
                    jsonObject = new JSONObject();
                }
                //根据工具名称获取工具实例并生成相应的结果格式
                BaseTool tool = toolManager.getTool(toolName);
                String result = tool.generateToolExecutedResult(jsonObject);
                // 输出前端和要持久化的内容
                String output = String.format("\n\n%s\n\n", result);
                chatHistoryStringBuilder.append(output);
                return output;
            }
            default -> {
                log.error("不支持的消息类型: {}", typeEnum);
                return "";
            }
        }
    }
}
