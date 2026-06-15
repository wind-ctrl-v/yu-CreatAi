package com.yu.yuaifather.agent.orchestrator;

import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.workers.ArchitectAgent;
import com.yu.yuaifather.agent.workers.CoderAgent;
import com.yu.yuaifather.agent.workers.CriticAgent;
import com.yu.yuaifather.agent.workers.DeployAgent;
import com.yu.yuaifather.agent.workers.ImageAgent;
import com.yu.yuaifather.agent.workers.ProductAgent;
import com.yu.yuaifather.agent.workers.StyleAgent;
import com.yu.yuaifather.ai.model.dto.CriticReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * OrchestratorAgent：多 Agent 流水线的总编排者。
 *
 * <h3>职责</h3>
 * <p>
 * Orchestrator 是整条流水线的"指挥官"，自身不调用任何 LLM，
 * 只负责按照固定顺序驱动各 Worker Agent 依次（或并发）执行，
 * 并在 Critic 质检未通过时触发重试循环。
 * </p>
 *
 * <h3>完整流水线顺序</h3>
 * <pre>
 *  ① ProductAgent    用户 prompt → 结构化需求文档（RequirementDoc）
 *        ↓ 串行
 *  ② ArchitectAgent  需求文档 → 生成计划（GenerationPlan：文件列表、路由、图片槽）
 *        ↓ 串行
 *  ③ ImageAgent      plan.imageSlots → imageMap（关键词 → 真实图片 URL）
 *        ↓ 串行（等 ImageAgent 完成，保证 Coder 写文件时 imageMap 已就绪）
 *  ④ CoderAgent  ┐  并发执行（Flux.merge）
 *     StyleAgent ┘  CoderAgent 按 plan 逐文件落盘；StyleAgent 生成全局 CSS
 *        ↓ 串行（等 Coder + Style 全部完成）
 *  ⑤ CriticAgent     静态检查 + LLM 评分 → CriticReport（score ≥ 80 通过）
 *        ↓ 若未通过且未超出最大轮次，回到 CoderAgent 修复，最多循环 criticMaxRounds 次
 *  ⑥ DeployAgent     （可选，受 agent.deploy.enabled 开关控制）
 *                    npm build → 上传 COS → 返回部署 URL
 * </pre>
 *
 * <h3>错误处理策略</h3>
 * <ul>
 *   <li>任意 Worker 抛出异常：被 {@code onErrorResume} 捕获，推送一个 ERROR 事件后终止流，
 *       不会抛到 SSE 层导致连接异常断开。</li>
 *   <li>Critic 超出最大轮次：降级推送状态事件，返回当前已生成的最佳结果，不终止流。</li>
 * </ul>
 *
 * <h3>开关说明</h3>
 * <ul>
 *   <li>{@code agent.orchestrator.enabled}（在 AppServiceImpl 控制）：
 *       整条多 Agent 流水线的总开关，false 时走经典单 LLM 链路。</li>
 *   <li>{@code agent.deploy.enabled}：DeployAgent 子开关，false 时跳过构建和上传步骤。</li>
 *   <li>{@code agent.critic.max-rounds}：Critic 反馈最大轮次，默认 3。</li>
 * </ul>
 */
@Slf4j
@Component
public class OrchestratorAgent implements Agent {

    /** SSE 事件中标识本 Agent 的名称，用于前端展示进度 */
    public static final String AGENT_NAME = "Orchestrator";

    /**
     * Critic 反馈循环的最大轮次，超出后降级返回当前结果。
     * 通过 {@code agent.critic.max-rounds} 配置，默认 3 轮。
     * <p>
     * 轮次含义：第 1 轮是首次质检；若未通过则 Coder 修复后进入第 2 轮质检，以此类推。
     * 超出后不再修复，直接把当前结果（即使不完美）推给用户。
     */
    @Value("${agent.critic.max-rounds:3}")
    private int criticMaxRounds;

    /**
     * 是否在流水线末尾执行 DeployAgent（npm build + 上传 COS）。
     * 通过 {@code agent.deploy.enabled} 配置，默认 false，避免每次生成都自动消耗构建资源。
     */
    @Value("${agent.deploy.enabled:false}")
    private boolean deployEnabled;

    // =====================================================================
    // Worker Agent 依赖注入
    // 全部通过构造函数注入，便于单元测试时 mock 替换
    // =====================================================================

    private final ProductAgent productAgent;
    private final ArchitectAgent architectAgent;
    private final ImageAgent imageAgent;
    private final CoderAgent coderAgent;
    private final StyleAgent styleAgent;
    private final CriticAgent criticAgent;
    private final DeployAgent deployAgent;

    /**
     * 构造函数注入所有 Worker Agent。
     * <p>
     * 使用构造函数注入而非 @Autowired 字段注入，原因：
     * <ol>
     *   <li>依赖关系在编译期显式声明，不会遗漏</li>
     *   <li>便于测试时传入 mock 对象</li>
     *   <li>保证对象构造完成后所有依赖均已就绪（final 字段）</li>
     * </ol>
     */
    @Autowired
    public OrchestratorAgent(ProductAgent productAgent,
                             ArchitectAgent architectAgent,
                             ImageAgent imageAgent,
                             CoderAgent coderAgent,
                             StyleAgent styleAgent,
                             CriticAgent criticAgent,
                             DeployAgent deployAgent) {
        this.productAgent = productAgent;
        this.architectAgent = architectAgent;
        this.imageAgent = imageAgent;
        this.coderAgent = coderAgent;
        this.styleAgent = styleAgent;
        this.criticAgent = criticAgent;
        this.deployAgent = deployAgent;
    }

    /** 返回本 Agent 的名称标识，用于 AgentEvent 中的 agentName 字段 */
    @Override
    public String name() {
        return AGENT_NAME;
    }

    /**
     * 构建并启动多 Agent 流水线，返回整条流水线产生的事件流。
     *
     * <h4>Reactor 实现要点</h4>
     * <ul>
     *   <li>{@code Flux.just(...).concatWith(...)}：串行拼接，上一个 Flux 完成后才订阅下一个，
     *       保证 ArchitectAgent 一定在 ProductAgent 完成后才开始。</li>
     *   <li>{@code Flux.merge(a, b)}：并发订阅 a 和 b，两者同时执行，
     *       事件按实际到达顺序交错输出（非严格顺序）。用于 Coder + Style 并发提速。</li>
     *   <li>{@code onErrorResume}：捕获流中任意位置的异常，转换为错误事件而非让流崩溃，
     *       保证前端 SSE 连接能收到错误信息并正常关闭。</li>
     * </ul>
     *
     * <h4>注意：这是惰性构建</h4>
     * <p>
     * 调用 {@code handle(ctx)} 时流水线并不立即执行，只是把各个 Flux 串联起来。
     * 真正执行发生在 Spring WebFlux 订阅（subscribe）这个 Flux 并开始推送 SSE 时。
     * </p>
     *
     * @param ctx 请求上下文，贯穿整条流水线，包含 appId、userPrompt 和共享 Blackboard
     * @return 整条流水线产生的事件流，每个事件会被 AppServiceImpl 序列化后推送给前端 SSE
     */
    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        // ① 串行构建主流水线：每个 concatWith 保证前一步完成后再启动下一步
        Flux<AgentEvent> pipeline = Flux.just(AgentEvent.status(AGENT_NAME, "流水线启动"))
                // Step1: 解析用户需求 → RequirementDoc 写入 Blackboard
                .concatWith(productAgent.handle(ctx))
                // Step2: 技术选型 + 文件规划 → GenerationPlan 写入 Blackboard
                .concatWith(architectAgent.handle(ctx))
                // Step3: 搜图 → imageMap 写入 Blackboard
                //        必须在 Coder 之前完成，确保 {{IMG:keyword}} 替换有素材
                .concatWith(imageAgent.handle(ctx))
                // Step4: Coder 和 Style 并发执行（互不依赖，并发可缩短总耗时）
                //        Flux.merge 同时订阅两个 Flux，哪个有事件就先输出哪个
                //        两者都 complete 后，merge 才 complete，concatWith 才继续
                .concatWith(Flux.merge(coderAgent.handle(ctx), styleAgent.handle(ctx)))
                // Step5: Critic 质检 + 反馈循环（内部最多重试 criticMaxRounds 次）
                .concatWith(runCriticLoop(ctx));

        // Step6（可选）: 构建并部署，受 agent.deploy.enabled 开关控制
        if (deployEnabled) {
            pipeline = pipeline.concatWith(deployAgent.handle(ctx));
        }

        return pipeline
                // 流水线全部完成后追加一个"完成"状态事件，前端可据此关闭进度条
                .concatWith(Flux.just(AgentEvent.status(AGENT_NAME, "流水线完成")))
                // 全局异常兜底：任意 Worker 抛出未捕获的异常都会到这里
                // onErrorResume 把异常转换为一个 ERROR 事件，让流正常终止（而非异常终止）
                // 这样前端 SSE 连接能收到错误信息，而不是看到连接突然断开
                .onErrorResume(err -> {
                    log.error("Orchestrator 流水线异常 appId={}", ctx.getAppId(), err);
                    return Flux.just(AgentEvent.error(AGENT_NAME, "流水线失败: " + err.getMessage()));
                });
    }

    /**
     * 启动 Critic 反馈循环的入口，从第 1 轮开始。
     *
     * <p>使用 {@code Flux.defer()} 包装的原因：
     * defer 保证内部逻辑在真正被订阅时才执行（惰性求值），
     * 避免在构建流水线时就立即调用 {@code doCriticRound}，
     * 确保此时 CoderAgent 已经执行完毕、Blackboard 中有生成好的文件可供质检。</p>
     *
     * @param ctx 请求上下文
     * @return Critic 反馈循环产生的事件流
     */
    private Flux<AgentEvent> runCriticLoop(AgentContext ctx) {
        // Flux.defer：订阅时才执行 lambda，保证惰性求值
        return Flux.defer(() -> doCriticRound(ctx, 1));
    }

    /**
     * 单轮 Critic 质检 + 条件分支（是否继续修复）。
     *
     * <h4>执行流程</h4>
     * <pre>
     *  执行本轮质检（CriticAgent）
     *      ↓
     *  读取 Blackboard 中的最新 CriticReport
     *      ├─ report == null 或 passed == true  → 质检通过，返回空流（结束循环）
     *      ├─ round >= criticMaxRounds          → 超出限制，降级推状态事件，结束循环
     *      └─ 未通过 且 未超限                  → 推"修复"状态事件 → CoderAgent 修复
     *                                               → 递归调用 doCriticRound(round + 1)
     * </pre>
     *
     * <h4>为何用递归而不是 for 循环</h4>
     * <p>
     * Reactor 是异步流式的，不能用命令式 for 循环控制异步步骤。
     * 递归 + {@code Flux.defer()} 是 Reactor 中实现"条件重试循环"的惯用模式：
     * 每轮结束后通过 {@code concatWith(Flux.defer(...))} 决定是否追加下一轮，
     * 直到满足退出条件（通过 或 超限）时返回空流 {@code Flux.empty()} 结束递归。
     * </p>
     *
     * @param ctx   请求上下文
     * @param round 当前质检轮次（从 1 开始）
     * @return 本轮及后续所有轮次产生的事件流
     */
    private Flux<AgentEvent> doCriticRound(AgentContext ctx, int round) {
        // 构建本轮的事件流：先推"第 N 轮"状态，再执行 CriticAgent
        Flux<AgentEvent> step = Flux.just(AgentEvent.status(AGENT_NAME, "质检第 " + round + " 轮"))
                .concatWith(criticAgent.handle(ctx));

        // concatWith(Flux.defer(...))：等本轮 CriticAgent 执行完毕后，
        // 再惰性求值决定下一步该做什么（此时 Blackboard 中才有最新的 CriticReport）
        return step.concatWith(Flux.defer(() -> {
            // 从黑板读取本轮刚写入的质检报告
            CriticReport report = ctx.getBlackboard().getCriticReport();

            // 分支1：质检通过（或报告为 null 视为通过），返回空流结束循环
            if (report == null || report.isPassed()) {
                return Flux.empty();
            }

            // 分支2：已达最大轮次，不再重试，降级推状态事件后结束循环
            // 降级而非报错，保证用户能拿到已生成的代码（即使不完美）
            if (round >= criticMaxRounds) {
                return Flux.just(AgentEvent.status(AGENT_NAME,
                        "质检超出最大轮次（" + criticMaxRounds + "），降级返回当前结果"));
            }

            // 分支3：质检未通过且未超限，推"修复"事件 → CoderAgent 修复 → 递归进入下一轮
            // 注意：CoderAgent 修复时会读取 Blackboard 中的 CriticReport.issues，
            //       因此 Critic 的问题清单对下一轮 Coder 是可见的（前提是 Coder 把它拼入 prompt）
            return Flux.just(AgentEvent.status(AGENT_NAME, "质检未通过，进入第 " + (round + 1) + " 轮修复"))
                    // CoderAgent 根据 Critic 指出的问题重新生成/修复代码
                    .concatWith(coderAgent.handle(ctx))
                    // 递归：进入下一轮质检，round + 1 确保最终一定会到达 criticMaxRounds
                    .concatWith(doCriticRound(ctx, round + 1));
        }));
    }
}
