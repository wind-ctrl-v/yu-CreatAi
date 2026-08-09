package com.yu.yuaifather.service;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yu.yuaifather.codegen.memory.CompressingChatMemoryStore;
import com.yu.yuaifather.codegen.memory.CompressingWindowChatMemory;
import com.yu.yuaifather.codegen.tools.*;
import com.yu.yuaifather.codegen.stream.model.AiResponseMessage;
import com.yu.yuaifather.codegen.stream.model.ToolExecutedMessage;
import com.yu.yuaifather.codegen.stream.model.ToolRequestMessage;
import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.*;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.time.Duration;

/**
 * 增量编辑服务：利用 AI + 文件工具对已有项目做原地修改，不重新生成整个项目。
 * 提供同步（普通 POST）和异步流式（SSE）两种接口。
 */
@Slf4j
@Service
public class IncrementalEditService {

    private final ChatModel chatModel;
    private final StreamingChatModel reasoningStreamingChatModel;
    private final ToolManager toolManager;
    private final CompressingChatMemoryStore compressingChatMemoryStore;

    @Value("${agent.memory.max-messages:100}")
    private int maxMessages;

    /**
     * AI 服务实例缓存（仿 AiCodeGeneratorServiceFactory）：避免每请求重建实例与重复绑定工具。
     * 记忆接入与主链路同一个 CompressingChatMemoryStore，共享 appId 记忆，
     * 使连续可视化修改能看到"上一次改了什么"。
     */
    private final Cache<Long, IncrementalEditAi> editAiCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    private final Cache<Long, IncrementalEditStreamAi> editStreamAiCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    public IncrementalEditService(ChatModel chatModel,
                                   StreamingChatModel reasoningStreamingChatModel,
                                   ToolManager toolManager,
                                   CompressingChatMemoryStore compressingChatMemoryStore) {
        this.chatModel = chatModel;
        this.reasoningStreamingChatModel = reasoningStreamingChatModel;
        this.toolManager = toolManager;
        this.compressingChatMemoryStore = compressingChatMemoryStore;
    }

    /**
     * 同步增量编辑 AI 接口
     */
    interface IncrementalEditAi {
        @SystemMessage("""
                你是前端代码编辑器。用户消息包含【目标元素】选择器+HTML+文本，以及【修改要求】。
                
                执行步骤（必须调用工具，不要跳过任何一步）：
                1. 用 readDir("src") 查看 src 目录结构，定位目标 .vue 文件
                2. 用 readFile 读取该 .vue 文件的完整内容
                3. 用 modifyFile 替换相关代码，完成修改
                4. 回复"修改完成"
                
                重要：
                - __ve_selected__ 和 __ve_hover__ class 是选择器辅助标记，修改时保留
                - 用户选择器中的 class 层级（如 div.home > section.hero）对应 .vue 文件中的 <template> 结构
                """)
        String edit(@MemoryId Long appId, @UserMessage String userRequest);
    }

    /**
     * 流式增量编辑 AI 接口（用于 SSE）
     */
    interface IncrementalEditStreamAi {
        @SystemMessage("""
                你是前端代码编辑器。用户消息包含【目标元素】选择器+HTML+文本，以及【修改要求】。
                每次调用工具前，先输出一句进度说明（一句话即可），然后调用工具。
                
                执行步骤（必须调用工具）：
                1. 输出"正在查看项目结构..."，调用 readDir("src")
                2. 根据目录结果，输出"正在读取 [文件名]..."，调用 readFile 读取目标 .vue 文件
                3. 输出"正在修改 [文件名]..."，调用 modifyFile 替换代码
                4. 输出"✅ 修改完成"
                
                重要：
                - __ve_selected__ / __ve_hover__ class 是选择器辅助标记，修改时保留
                - 用户选择器中的 class 层级对应 .vue 文件的 <template> 结构
                """)
        TokenStream editStream(@MemoryId Long appId, @UserMessage String userRequest);
    }

    /**
     * 对已生成的项目执行增量修改（同步，非流式）。
     */
    public String incrementalEdit(Long appId, String instruction) {
        String dirName = CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId;
        File projectDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR, dirName);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            return "项目目录不存在，请先生成代码";
        }

        IncrementalEditAi ai = getEditAi(appId);

        try {
            String result = ai.edit(appId, instruction);
            log.info("增量编辑完成 appId={}", appId);
            return result;
        } catch (Exception e) {
            log.error("增量编辑失败 appId={}", appId, e);
            return "编辑失败: " + e.getMessage();
        }
    }

    /**
     * 按 appId 获取同步增量编辑 AI 实例（带缓存）
     */
    private IncrementalEditAi getEditAi(Long appId) {
        return editAiCache.get(appId, this::createEditAi);
    }

    private IncrementalEditAi createEditAi(Long appId) {
        ChatMemory chatMemory = new CompressingWindowChatMemory(appId, compressingChatMemoryStore, maxMessages);
        return AiServices.builder(IncrementalEditAi.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> chatMemory)
                .tools((Object[]) toolManager.getAllTools())
                .build();
    }

    /**
     * 按 appId 获取流式增量编辑 AI 实例（带缓存）
     */
    private IncrementalEditStreamAi getEditStreamAi(Long appId) {
        return editStreamAiCache.get(appId, this::createEditStreamAi);
    }

    private IncrementalEditStreamAi createEditStreamAi(Long appId) {
        ChatMemory chatMemory = new CompressingWindowChatMemory(appId, compressingChatMemoryStore, maxMessages);
        return AiServices.builder(IncrementalEditStreamAi.class)
                .streamingChatModel(reasoningStreamingChatModel)
                .chatMemoryProvider(memoryId -> chatMemory)
                .tools((Object[]) toolManager.getAllTools())
                .build();
    }

    /**
     * 对已生成的项目执行增量修改（流式 SSE）。
     * 返回 Flux<String>，每条数据为 JSON 字符串，包含 ai_response / tool_request / tool_executed 类型。
     */
    public Flux<String> incrementalEditStream(Long appId, String instruction) {
        String dirName = CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId;
        File projectDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR, dirName);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            return Flux.just("{\"type\":\"error\",\"message\":\"项目目录不存在，请先生成代码\"}");
        }

        IncrementalEditStreamAi ai = getEditStreamAi(appId);

        try {
            TokenStream tokenStream = ai.editStream(appId, instruction);
            return Flux.<String>create(sink -> {
                tokenStream
                    .onPartialResponse((String partialResponse) -> {
                        AiResponseMessage msg = new AiResponseMessage(partialResponse);
                        sink.next(JSONUtil.toJsonStr(msg));
                    })
                    .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                        ToolRequestMessage msg = new ToolRequestMessage(toolExecutionRequest);
                        sink.next(JSONUtil.toJsonStr(msg));
                    })
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        ToolExecutedMessage msg = new ToolExecutedMessage(toolExecution);
                        sink.next(JSONUtil.toJsonStr(msg));
                    })
                    .onCompleteResponse(response -> {
                        sink.complete();
                    })
                    .onError(error -> {
                        log.error("增量编辑流式失败 appId={}", appId, error);
                        sink.error(error);
                    })
                    .start();
            })
            .timeout(Duration.ofMinutes(5), Flux.error(
                    new BusinessException(ErrorCode.SYSTEM_ERROR, "增量编辑超时，请重试")))
            .onErrorResume(e -> {
                log.error("增量编辑流式异常 appId={}", appId, e);
                return Flux.just("{\"type\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
            });
        } catch (Exception e) {
            log.error("增量编辑流式初始化失败 appId={}", appId, e);
            return Flux.just("{\"type\":\"error\",\"message\":\"编辑失败: " + e.getMessage() + "\"}");
        }
    }
}
