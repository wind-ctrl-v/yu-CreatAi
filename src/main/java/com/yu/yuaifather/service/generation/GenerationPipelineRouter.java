package com.yu.yuaifather.service.generation;

import cn.hutool.json.JSONUtil;
import com.yu.yuaifather.codegen.service.AiCodeGeneratorFacade;
import com.yu.yuaifather.codegen.stream.model.AiResponseMessage;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.model.dto.generation.GenerationPolicy;
import com.yu.yuaifather.model.entity.GenerationRun;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import com.yu.yuaifather.model.enums.PipelineType;
import com.yu.yuaifather.orchestrator.GenerationOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 生成链路路由器：统一管理 <b>V2 → Classic</b> 两级降级。
 *
 * <h3>路由决策</h3>
 * <ul>
 *   <li>非 VUE_PROJECT 或多 Agent 开关关闭 → 直接走经典 {@link AiCodeGeneratorFacade}。</li>
 *   <li>VUE_PROJECT 且 V2 开关开启 → V2 {@link GenerationOrchestrator}，失败降级 Classic。</li>
 *   <li>其他情况 → 直接走经典 {@link AiCodeGeneratorFacade}。</li>
 * </ul>
 *
 * <p>本类把原先散落在 {@code AppServiceImpl.chatToGenCode} 第 6 步的内联 try/catch 降级逻辑收敛于此，
 * 并将仅"订阅前"的回退升级为<b>流中 {@code onErrorResume}</b>：各下游链路通过 {@link Flux#defer}
 * 延迟构建，无论是构建期异常还是流中异常都会触发降级。</p>
 *
 * <p>每次请求生成全局 {@code generationId} 贯穿各 attempt，并通过 {@link GenerationRunService}
 * 落库开始/降级/成功/失败，便于统计与可观测。各 attempt 物理工作目录隔离（避免共写
 * {@code {codeGenType}_{appId}} 的污染）需配套的写入拦截层，作为独立重构后续推进。</p>
 */
@Slf4j
@Component
public class GenerationPipelineRouter {

    /** V2 多 Agent 流水线开关：仅对 VUE_PROJECT 生效，默认关闭。 */
    @Value("${agent.orchestrator.v2.enabled:false}")
    private boolean orchestratorEnabled;

    private final GenerationOrchestrator generationOrchestrator;
    private final AiCodeGeneratorFacade aiCodeGeneratorFacade;
    private final GenerationModeResolver modeResolver;
    private final GenerationRunService generationRunService;
    private final QuotaService quotaService;

    @Autowired
    public GenerationPipelineRouter(GenerationOrchestrator generationOrchestrator,
                                    AiCodeGeneratorFacade aiCodeGeneratorFacade,
                                    GenerationModeResolver modeResolver,
                                    GenerationRunService generationRunService,
                                    QuotaService quotaService) {
        this.generationOrchestrator = generationOrchestrator;
        this.aiCodeGeneratorFacade = aiCodeGeneratorFacade;
        this.modeResolver = modeResolver;
        this.generationRunService = generationRunService;
        this.quotaService = quotaService;
    }

    /**
     * 路由并返回最终的代码生成流（已序列化为前端 SSE 文本）。
     * 调用方仅需将返回流交给统一收口（{@code StreamHandlerExecutor}）。
     *
     * @param appId         应用 ID
     * @param message       用户消息
     * @param requestedMode 前端请求模式（不可信意图，可为 null），由 Resolver 按套餐封顶
     * @param loginUser     当前登录用户
     * @param codeGenType   代码生成类型
     * @return SSE 文本流
     */
    public Flux<String> route(Long appId, String message, String requestedMode,
                              User loginUser, CodeGenTypeEnum codeGenType) {
        // 决策：身份/套餐/额度/复杂度统一收敛于此，得到不可变策略
        GenerationPolicy policy = modeResolver.resolve(requestedMode, loginUser, message, codeGenType);

        // 本次生成的全局追踪 ID（贯穿各 attempt）
        String generationId = "gen_" + UUID.randomUUID().toString().replace("-", "");
        Long userId = loginUser == null ? null : loginUser.getId();

        // 开始记录（容错，失败不阻断）
        GenerationRun run = generationRunService.start(generationId, appId, userId, requestedMode,
                policy.getGenerationMode().getValue(), policy.getPreferredPipeline(), codeGenType);

        AtomicBoolean fallbackOccurred = new AtomicBoolean(false);
        AtomicReference<PipelineType> activePipeline = new AtomicReference<>();

        // 经典兜底链路（延迟构建，可作为降级目标复用）
        Flux<String> classic = Flux.defer(() -> {
            activePipeline.set(PipelineType.CLASSIC);
            return aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenType, appId);
        });

        Flux<String> chosen;
        // 非 Vue 项目或多 Agent 开关关闭：直接走经典链路（行为与改造前一致）
        if (!orchestratorEnabled || codeGenType != CodeGenTypeEnum.VUE_PROJECT) {
            chosen = classic;
        } else if (policy.isV2Enabled()) {
            // V2 链路：失败按策略降级 Classic
            chosen = Flux.defer(() -> {
                activePipeline.set(PipelineType.V2);
                return generationOrchestrator.generate(
                                buildContext(appId, message, loginUser, codeGenType, generationId, "v2"), policy)
                        .map(this::toStreamPayload);
            }).onErrorResume(e -> {
                log.error("GenerationOrchestrator(V2) 异常 appId={} mode={}",
                        appId, policy.getGenerationMode(), e);
                if (policy.isAllowClassicFallback()) {
                    fallbackOccurred.set(true);
                    generationRunService.fallback(run, PipelineType.V2, PipelineType.CLASSIC, e.getMessage());
                    return Flux.concat(
                            Flux.just(fallbackPayload(PipelineType.V2, PipelineType.CLASSIC, e.getMessage())), classic);
                }
                return Flux.error(e);
            });
        } else {
            chosen = classic;
        }

        // 结构化 SSE：仅 VUE 路径在流首部推送 generation_started（与既有 AiResponseMessage 解析兼容）；
        // completed 由 Controller 末尾的 done 事件承载。简单文本路径(HTML/MULTI_FILE)不注入，避免污染产物。
        Flux<String> result = chosen;
        if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
            result = Flux.concat(Flux.just(startedPayload(generationId, policy)), chosen);
        }

        return result
                .doOnComplete(() -> {
                    generationRunService.success(run, activePipeline.get(), fallbackOccurred.get());
                    quotaService.recordUsage(loginUser, policy.getGenerationMode());
                })
                .doOnError(e -> generationRunService.fail(run, e.getMessage()));
    }

    /**
     * 构建多 Agent 上下文，附带本次 generationId 与 attempt 标识，便于全链路追踪。
     */
    private AgentContext buildContext(Long appId, String message, User loginUser,
                                      CodeGenTypeEnum codeGenType, String generationId, String attemptId) {
        return AgentContext.builder()
                .appId(appId)
                .loginUser(loginUser)
                .userPrompt(message)
                .codeGenType(codeGenType)
                .generationId(generationId)
                .pipelineAttemptId(attemptId)
                .build();
    }

    /**
     * 将 Agent 输出的 {@link AgentEvent} 序列化为前端 SSE 文本，复用既有 StreamHandlerExecutor 的解析格式。
     * <ul>
     *   <li>TEXT_CHUNK / TOOL_CALL / TOOL_RESULT 已是 SSE 协议字符串，原样透传；</li>
     *   <li>STATUS_CHANGE / ERROR 以 {@link AiResponseMessage} 包装为可读文本。</li>
     * </ul>
     */
    private String toStreamPayload(AgentEvent event) {
        if (event.getType() == AgentEvent.EventType.TEXT_CHUNK
                || event.getType() == AgentEvent.EventType.TOOL_CALL
                || event.getType() == AgentEvent.EventType.TOOL_RESULT) {
            return event.getContent() == null ? "" : event.getContent();
        }
        String prefix = event.getType() == AgentEvent.EventType.ERROR ? "[错误]" : "[阶段]";
        String text = String.format("\n%s %s：%s\n", prefix,
                event.getAgentName() == null ? "" : event.getAgentName(),
                event.getContent() == null ? "" : event.getContent());
        return JSONUtil.toJsonStr(new AiResponseMessage(text));
    }

    /**
     * 构造 generation_started 结构化事件（包装为 AiResponseMessage，兼容既有解析）。
     */
    private String startedPayload(String generationId, GenerationPolicy policy) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "generation_started");
        event.put("generationId", generationId);
        event.put("pipeline", policy.getPreferredPipeline() == null ? "V2" : policy.getPreferredPipeline().name());
        event.put("mode", policy.getGenerationMode().getValue());
        event.put("fallbackEnabled", policy.isAllowClassicFallback());
        return JSONUtil.toJsonStr(new AiResponseMessage("\n[生成] " + JSONUtil.toJsonStr(event) + "\n"));
    }

    /**
     * 构造 pipeline_fallback 结构化事件。
     */
    private String fallbackPayload(PipelineType from, PipelineType to, String reason) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "pipeline_fallback");
        event.put("from", from == null ? null : from.name());
        event.put("to", to == null ? null : to.name());
        event.put("reason", reason == null ? "" : reason);
        return JSONUtil.toJsonStr(new AiResponseMessage("\n[降级] " + JSONUtil.toJsonStr(event) + "\n"));
    }
}
