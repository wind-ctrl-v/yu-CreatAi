package com.yu.yuaifather.agent.workers;

import cn.hutool.json.JSONUtil;
import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.support.ImagePlaceholderReplacer;
import com.yu.yuaifather.ai.AiCodeGeneratorService;
import com.yu.yuaifather.ai.AiCodeGeneratorServiceFactory;
import com.yu.yuaifather.ai.enums.CodeGenTypeEnum;
import com.yu.yuaifather.ai.model.dto.GenerationPlan;
import com.yu.yuaifather.ai.model.message.AiResponseMessage;
import com.yu.yuaifather.ai.model.message.ToolExecutedMessage;
import com.yu.yuaifather.ai.model.message.ToolRequestMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * CoderAgent：复用现有 Vue 项目流式生成（带 File 工具）按 plan 把 fileStructure 落盘。
 * <p>
 * 流式完成后触发图片占位符替换。
 */
@Slf4j
@Component
public class CoderAgent implements Agent {

    public static final String AGENT_NAME = "CoderAgent";

    private final AiCodeGeneratorServiceFactory factory;
    private final ImagePlaceholderReplacer placeholderReplacer;

    @Autowired
    public CoderAgent(AiCodeGeneratorServiceFactory factory,
                      ImagePlaceholderReplacer placeholderReplacer) {
        this.factory = factory;
        this.placeholderReplacer = placeholderReplacer;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.create(sink -> {
            sink.next(AgentEvent.status(AGENT_NAME, "开始生成代码"));
            GenerationPlan plan = ctx.getBlackboard().getPlan();
            String userMessage = buildUserMessage(ctx.getUserPrompt(), plan);
            AiCodeGeneratorService service = factory.getAiCodeGeneratorService(
                    ctx.getAppId(), CodeGenTypeEnum.VUE_PROJECT);
            TokenStream stream = service.generateVueProjectCodeStream(ctx.getAppId(), userMessage);
            stream.onPartialResponse(partial -> sink.next(AgentEvent.builder()
                            .type(AgentEvent.EventType.TEXT_CHUNK)
                            .agentName(AGENT_NAME)
                            .content(JSONUtil.toJsonStr(new AiResponseMessage(partial)))
                            .build()))
                    .onPartialToolExecutionRequest((idx, req) -> sink.next(AgentEvent.builder()
                            .type(AgentEvent.EventType.TOOL_CALL)
                            .agentName(AGENT_NAME)
                            .content(JSONUtil.toJsonStr(new ToolRequestMessage(req)))
                            .data(req)
                            .build()))
                    .onToolExecuted((ToolExecution exec) -> {
                        try {
                            String args = exec.request() == null ? "" : exec.request().arguments();
                            if (args != null && args.contains("relativeFilePath")) {
                                String filePath = JSONUtil.parseObj(args).getStr("relativeFilePath");
                                ctx.getBlackboard().addGeneratedFile(filePath);
                            }
                        } catch (Exception ignore) {
                            // 解析失败不影响主流程
                        }
                        sink.next(AgentEvent.builder()
                                .type(AgentEvent.EventType.TOOL_RESULT)
                                .agentName(AGENT_NAME)
                                .content(JSONUtil.toJsonStr(new ToolExecutedMessage(exec)))
                                .build());
                    })
                    .onCompleteResponse(resp -> {
                        try {
                            placeholderReplacer.replaceAll(ctx.getAppId());
                        } catch (Exception e) {
                            log.warn("图片占位符替换失败 appId={} msg={}", ctx.getAppId(), e.getMessage());
                        }
                        sink.next(AgentEvent.status(AGENT_NAME, "代码生成完成"));
                        sink.complete();
                    })
                    .onError(err -> {
                        log.error("CoderAgent 流式生成失败 appId={}", ctx.getAppId(), err);
                        sink.next(AgentEvent.error(AGENT_NAME, "代码生成失败: " + err.getMessage()));
                        sink.error(err);
                    })
                    .start();
        });
    }

    /**
     * 拼装传给 LLM 的用户消息：原始 prompt + plan 摘要，便于模型按 plan 落盘。
     */
    private String buildUserMessage(String userPrompt, GenerationPlan plan) {
        if (plan == null) {
            return userPrompt;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("原始需求：").append(userPrompt).append("\n\n");
        sb.append("请严格按以下生成计划落盘代码：\n");
        sb.append(JSONUtil.toJsonStr(plan));
        sb.append("\n\n务必使用【文件写入工具】逐文件创建，图片资源全部使用 {{IMG:英文关键词}} 占位符。");
        return sb.toString();
    }
}
