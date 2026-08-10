package com.yu.yuaifather.codegen.service;

import cn.hutool.json.JSONUtil;
import com.yu.yuaifather.codegen.memory.ChatMemoryIds;
import com.yu.yuaifather.codegen.memory.CompressingChatMemoryStore;
import com.yu.yuaifather.service.design.ImagePlaceholderReplacer;
import com.yu.yuaifather.codegen.parser.CodeParserExecutor;
import com.yu.yuaifather.codegen.saver.CodeFileSaverExecutor;
import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import com.yu.yuaifather.codegen.stream.model.AiResponseMessage;
import com.yu.yuaifather.codegen.stream.model.ToolExecutedMessage;
import com.yu.yuaifather.codegen.stream.model.ToolRequestMessage;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.time.Duration;

/**
 * AI 代码生成外观类，组合生成和保存功能
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {


    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Resource
    private ImagePlaceholderReplacer imagePlaceholderReplacer;

    @Resource
    private CompressingChatMemoryStore compressingChatMemoryStore;

    /**
     * 统一入口：根据类型生成并保存代码（流式，使用 appId）
     *
     * <p>超限自愈：捕获 context_length_exceeded 时重置该 appId 的记忆，带当前消息重试一次，
     * 避免长会话撞上限后用户卡死（分层渲染后极少触发，此为兜底）。</p>
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        return Flux.defer(() -> doGenerateAndSaveCodeStream(userMessage, codeGenTypeEnum, appId))
                .onErrorResume(error -> {
                    if (!isContextLengthExceeded(error)) {
                        return Flux.error(error);
                    }
                    log.warn("检测到上下文超限，重置记忆后重试一次 appId={} err={}", appId, error.getMessage());
                    resetMemory(appId);
                    return Flux.defer(() -> doGenerateAndSaveCodeStream(userMessage, codeGenTypeEnum, appId));
                });
    }

    /**
     * 重置某个应用的对话记忆。
     *
     * <p>模型上下文来自 AI 服务实例内部的内存记忆窗口，因此必须失效实例本身：
     * 下次请求重建实例时从 MySQL 冷加载有限条数，上下文规模必然小于触发超限时的窗口。
     * 只删 Redis 快照对模型上下文毫无作用（快照不回读），但顺带清掉已失真的备份是合理的。</p>
     */
    private void resetMemory(Long appId) {
        if (appId == null) {
            return;
        }
        try {
            aiCodeGeneratorServiceFactory.invalidate(appId);
        } catch (Exception e) {
            log.warn("失效 AI 服务实例失败 appId={} err={}", appId, e.getMessage());
        }
        for (String memoryId : ChatMemoryIds.allOf(appId)) {
            try {
                compressingChatMemoryStore.deleteMessages(memoryId);
            } catch (Exception e) {
                log.warn("清空对话记忆快照失败 memoryId={} err={}", memoryId, e.getMessage());
            }
        }
    }

    /**
     * 判断异常链中是否存在上下文超限错误（OpenAI / DeepSeek 兼容协议的错误形态）
     */
    private boolean isContextLengthExceeded(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("context_length_exceeded") || lower.contains("maximum context length")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 实际执行生成并保存代码（流式）
     */
    private Flux<String> doGenerateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        // 根据 appId 获取对应的 AI 服务实例
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId,codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateHtmlCodeStream(appId, userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(appId, userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            case VUE_PROJECT -> {
                TokenStream codeStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage);
                yield processTokenStream(codeStream, appId);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }


    /**
     * 将 TokenStream 转换为 Flux<String>，并传递工具调用信息。
     * <p>
     * 在流完成后，对 Vue 项目目录中的 {@code @@IMG:keyword@@} 占位符进行真实图片替换。
     *
     * @param tokenStream TokenStream 对象
     * @param appId       应用 ID（用于定位项目目录与替换图片占位符）
     * @return Flux<String> 流式响应
     */
    private Flux<String> processTokenStream(TokenStream tokenStream, Long appId) {
        return Flux.<String>create(sink -> {
            tokenStream.onPartialResponse((String partialResponse) -> {
                        AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                        sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                    })
                    .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                        ToolRequestMessage toolRequestMessage = new ToolRequestMessage(toolExecutionRequest);
                        sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                    })
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                        sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
                    })
                    .onCompleteResponse((ChatResponse response) -> {
                        // 流结束后异步替换图片占位符（失败不影响主流程）
                        try {
                            imagePlaceholderReplacer.replaceAll(appId);
                        } catch (Exception e) {
                            log.warn("图片占位符替换失败 appId={} msg={}", appId, e.getMessage());
                        }
                        sink.complete();
                    })
                    .onError((Throwable error) -> {
                        log.error("Vue 项目流式生成失败 appId={}", appId, error);
                        sink.error(error);
                    })
                    .start();
        })
        // 添加超时保护，防止 TokenStream 回调永不触发导致 Flux 挂起
        .timeout(Duration.ofMinutes(10), Flux.error(
                new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 代码生成超时，请重试")));
    }



    /**
     * 通用流式代码处理方法（使用 appId）
     *
     * @param codeStream  代码流
     * @param codeGenType 代码生成类型
     * @param appId       应用 ID
     * @return 流式响应
     */
    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType, Long appId) {
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream.doOnNext(chunk -> {
            // 实时收集代码片段
            codeBuilder.append(chunk);
        }).doOnComplete(() -> {
            // 流式返回完成后保存代码
            try {
                String completeCode = codeBuilder.toString();
                // 使用执行器解析代码
                Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
                // 使用执行器保存代码
                File savedDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                log.info("保存成功，路径为：" + savedDir.getAbsolutePath());
            } catch (Exception e) {
                log.error("保存失败: {}", e.getMessage());
            }
        });
    }







}
