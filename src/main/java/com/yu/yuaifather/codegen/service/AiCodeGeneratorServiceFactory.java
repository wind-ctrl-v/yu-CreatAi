package com.yu.yuaifather.codegen.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yu.yuaifather.codegen.memory.ChatMemoryFactory;
import com.yu.yuaifather.observability.AttributingChatModel;
import com.yu.yuaifather.observability.AttributingStreamingChatModel;
import com.yu.yuaifather.codegen.memory.ChatMemoryIds;
import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import com.yu.yuaifather.codegen.tools.*;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.service.ChatHistoryService;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@Slf4j
public class AiCodeGeneratorServiceFactory {

    @Resource
    private ChatModel chatModel;

    @Resource
    private StreamingChatModel openAiStreamingChatModel;

    @Resource
    private  StreamingChatModel reasoningStreamingChatModel;

    @Resource
    private ChatMemoryFactory chatMemoryFactory;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private ToolManager toolManager;

    /**
     * 冷启动从 MySQL 加载的历史条数
     */
    @Value("${agent.memory.load-count:20}")
    private int historyLoadCount;


    /**
     * 默认提供一个 Bean
     */
    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService() {
        return getAiCodeGeneratorService(0L);
    }


    /**
     * AI 服务实例缓存
     * 缓存策略：
     * - 最大缓存 200 个实例（每个实例持有一份全文消息窗口，容量不宜过大）
     * - 写入后 15 分钟过期
     * - 访问后 10 分钟过期
     */
    private final Cache<String, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(Duration.ofMinutes(15))
            .expireAfterAccess(Duration.ofMinutes(10))
            .removalListener((key, value, cause) -> {
                log.debug("AI 服务实例被移除，缓存键: {}, 原因: {}", key, cause);
            })
            .build();

    /**
     * 根据 appId 获取服务（带缓存）这个方法是为了兼容历史逻辑
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId) {
        return getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML);
    }

    /**
     * 根据 appId 和代码生成类型获取服务（带缓存）
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        String cacheKey = buildCacheKey(appId, codeGenType);
        return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType));
    }

    /**
     * 构建缓存键
     */
    private String buildCacheKey(long appId, CodeGenTypeEnum codeGenType) {
        return appId + "_" + codeGenType.getValue();
    }

    /**
     * 失效某个应用的全部 AI 服务实例。
     *
     * <p>模型上下文由实例内部的内存记忆窗口承载，因此「清空记忆」必须落到实例上：
     * 实例失效后下次请求会重建并从 MySQL 冷加载（条数受 {@code agent.memory.load-count} 限制），
     * 上下文规模必然小于触发超限时的窗口。仅删 Redis 快照对模型上下文没有任何作用。</p>
     */
    public void invalidate(long appId) {
        for (CodeGenTypeEnum codeGenType : CodeGenTypeEnum.values()) {
            serviceCache.invalidate(buildCacheKey(appId, codeGenType));
        }
        log.info("已失效应用的AI 服务实例与内存记忆 appId={}", appId);
    }


    /**
     * 创建新的 AI 服务实例
     */
    private AiCodeGeneratorService createAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        // 内存优先对话记忆：模型读内存未压缩消息（渲染时按轮次分层压缩），Redis 只做快照备份
        // memoryId 携带用途后缀，避免同一 appId 的多个实例互相覆盖 Redis 快照
        ChatMemory chatMemory = chatMemoryFactory.create(ChatMemoryIds.forCodeGen(appId, codeGenType));
        // 从 MySQL 加载历史对话全文到内存记忆（不过压缩器）
        chatHistoryService.loadChatHistoryToMemory(appId, chatMemory, historyLoadCount);
        // 根据代码生成类型选择不同的模型配置
        return switch (codeGenType) {
            // Vue 项目生成使用推理模型
            case VUE_PROJECT -> AiServices.builder(AiCodeGeneratorService.class)
                    .streamingChatModel(AttributingStreamingChatModel.wrap(reasoningStreamingChatModel))
                    .chatMemoryProvider(memoryId -> chatMemory)
                    .tools(toolManager.getAllTools())
                    .hallucinatedToolNameStrategy(toolExecutionRequest -> ToolExecutionResultMessage.from(
                            toolExecutionRequest, "Error: there is no tool called " + toolExecutionRequest.name()
                    ))
                    .build();

            // HTML 和多文件生成使用默认模型
            // 修复：使用 chatMemoryProvider 与 @MemoryId 配合
            case HTML, MULTI_FILE -> AiServices.builder(AiCodeGeneratorService.class)
                    .chatModel(AttributingChatModel.wrap(chatModel))
                    .streamingChatModel(AttributingStreamingChatModel.wrap(openAiStreamingChatModel))
                    .chatMemoryProvider(memoryId -> chatMemory)
                    .build();
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "不支持的代码生成类型: " + codeGenType.getValue());
        };
    }







}


