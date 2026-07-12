package com.yu.yuaifather.orchestrator.v1;

import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.workers.CoderAgent;
import com.yu.yuaifather.agent.workers.CriticAgent;
import com.yu.yuaifather.agent.workers.PlanningAgent;
import com.yu.yuaifather.agent.workers.StyleAgent;
import com.yu.yuaifather.model.dto.agent.CriticReport;
import com.yu.yuaifather.tool.system.DesignTokenTool;
import com.yu.yuaifather.tool.system.DesignRetrieveTool;
import com.yu.yuaifather.tool.system.DeployTool;
import com.yu.yuaifather.tool.system.ProjectProfileTool;
import com.yu.yuaifather.tool.system.UserPreferenceTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * OrchestratorAgent：多 Agent 流水线的总编排者。
 * <p>
 * ★ 包结构说明：本类原位于 {@code agent.orchestrator} 子包，现迁移到 {@code orchestrator.v1}，
 * 与 {@code orchestrator.v2.GenerationOrchestrator} 统一层级划分（见包结构优化计划问题 3）。
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
 *  ① PlanningAgent     用户 prompt → RequirementDoc + GenerationPlan（合并 Product+Architect）
 *        ↓ 串行
 *  ② DesignTokenTool    styleProfile → DesignSpec（零 LLM）
 *        ↓ 串行
 *  ③ UserPreferenceTool 用户偏好加载（零 LLM）
 *        ↓ 串行
 *  ④ ProjectProfileTool 项目品牌加载（零 LLM）
 *        ↓ 串行
 *  ⑤ DesignRetrieveTool 向量检索 + 权限过滤（消耗 Embedding Token）
 *        ↓ 串行
 *  ⑥ ImageSearchTool    plan.imageSlots → imageMap（零 LLM）
 *        ↓ 串行
 *  ⑦ CoderAgent  ┐  并发执行（Flux.merge）
 *     StyleAgent ┘  CoderAgent 按 plan 逐文件落盘；StyleAgent 生成全局 CSS
 *        ↓ 串行
 *  ⑧ CriticAgent       静态检查 + LLM 评分 → CriticReport（score ≥ 80 通过）
 *        ↓ 若未通过且未超出最大轮次，回到 CoderAgent 修复
 *  ⑨ DeployTool        构建 + 部署（零 LLM，可选）
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

    private final PlanningAgent planningAgent;
    private final DesignTokenTool designTokenTool;
    private final UserPreferenceTool userPreferenceTool;
    private final ProjectProfileTool projectProfileTool;
    private final DesignRetrieveTool designRetrieveTool;
    private final CoderAgent coderAgent;
    private final StyleAgent styleAgent;
    private final CriticAgent criticAgent;
    private final DeployTool deployTool;

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
    public OrchestratorAgent(PlanningAgent planningAgent,
                             DesignTokenTool designTokenTool,
                             @Autowired(required = false) UserPreferenceTool userPreferenceTool,
                             @Autowired(required = false) ProjectProfileTool projectProfileTool,
                             @Autowired(required = false) DesignRetrieveTool designRetrieveTool,
                             CoderAgent coderAgent,
                             StyleAgent styleAgent,
                             CriticAgent criticAgent,
                             DeployTool deployTool) {
        this.planningAgent = planningAgent;
        this.designTokenTool = designTokenTool;
        this.userPreferenceTool = userPreferenceTool;
        this.projectProfileTool = projectProfileTool;
        this.designRetrieveTool = designRetrieveTool;
        this.coderAgent = coderAgent;
        this.styleAgent = styleAgent;
        this.criticAgent = criticAgent;
        this.deployTool = deployTool;
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
                // Step1-2: PlanningAgent 一次完成需求解析 + 技术选型（合并 ProductAgent + ArchitectAgent）
                .concatWith(planningAgent.handle(ctx))
                // Step2.5: 加载行业设计规范 → DesignSpec 写入 Blackboard（零 LLM）
                .concatWith(designTokenTool.handle(ctx))
                // Step2.55: 加载用户设计偏好 → UserDesignContext 写入 Blackboard（阶段一新增）
                .concatWith(userPreferenceTool != null
                        ? userPreferenceTool.handle(ctx)
                        : Flux.just(AgentEvent.status(AGENT_NAME, "UserPreferenceTool 未加载，跳过用户偏好")))
                // Step2.56: 加载项目品牌画像 → ProjectBrandContext 写入 Blackboard（阶段三新增）
                .concatWith(projectProfileTool != null
                        ? projectProfileTool.handle(ctx)
                        : Flux.just(AgentEvent.status(AGENT_NAME, "ProjectProfileTool 未加载，跳过项目品牌")))
                // Step2.6: 运行时召回受控设计证据 → DesignEvidence 写入 Blackboard（阶段三，开关默认关闭）
                .concatWith(designRetrieveTool != null
                        ? designRetrieveTool.handle(ctx)
                        : Flux.just(AgentEvent.status(AGENT_NAME, "DesignRetrieveTool 未加载，跳过召回（缺少 EmbeddingModel）")))
                // Step3: 搜图已改为生成后占位符回填（ImagePlaceholderReplacer → ImageSearchEngine），不再预搜图
                // Step4: Coder 和 Style 并发执行
                .concatWith(Flux.merge(coderAgent.handle(ctx), styleAgent.handle(ctx)))
                // Step5: Critic 质检 + 反馈循环
                .concatWith(runCriticLoop(ctx));

        // Step6（可选）: 构建并部署
        if (deployEnabled) {
            pipeline = pipeline.concatWith(deployTool.handle(ctx));
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
        // 初始：第 1 轮，无上一轮修复分支，乒乓计数 0
        return Flux.defer(() -> doCriticRound(ctx, 1, null, 0));
    }

    // 修复分支标识：仅样式 / 仅代码 / 两者皆有
    private static final String BRANCH_STYLE = "STYLE";
    private static final String BRANCH_CODER = "CODER";
    private static final String BRANCH_BOTH = "BOTH";

    /**
     * 单轮 Critic 质检 + 按 issue 分类的修复分支（评审 #9 / 计划 1.5.4）。
     *
     * <h4>执行流程</h4>
     * <pre>
     *  执行本轮质检（CriticAgent）
     *      ↓
     *  读取 Blackboard 中的最新 CriticReport
     *      ├─ report == null 或 passed == true       → 通过，结束循环
     *      ├─ round >= criticMaxRounds                → 超限降级，结束循环
     *      ├─ 连续两轮 Style↔Coder 来回未通过          → 强制收敛降级，结束循环
     *      └─ 未通过且未超限                          → 按 issue 分类选择修复分支：
     *               · 仅样式类      → 重跑 StyleAgent
     *               · 仅代码类      → 重跑 CoderAgent
     *               · 两类皆有      → 先 StyleAgent 再 CoderAgent（串行，仍计 1 轮）
     *           → 递归进入下一轮质检（round + 1）
     * </pre>
     *
     * <h4>轮次记账与乒乓抑制</h4>
     * <ul>
     *   <li>轮次按「质检次数」统一计：无论本轮重跑 Coder / Style / 还是先 Style 再 Coder，
     *       都只占用 1 个轮次预算，不另开计数器；</li>
     *   <li>记录上一轮修复分支，若本轮与上一轮在 Style↔Coder 之间来回，乒乓计数 +1；
     *       连续两次来回（pingpong ≥ 2）则强制收敛降级，避免无意义乒乓循环。</li>
     * </ul>
     *
     * @param ctx          请求上下文
     * @param round        当前质检轮次（从 1 开始）
     * @param lastBranch   上一轮的修复分支（首轮为 null）
     * @param pingpongCount 连续 Style↔Coder 来回次数
     * @return 本轮及后续所有轮次产生的事件流
     */
    private Flux<AgentEvent> doCriticRound(AgentContext ctx, int round, String lastBranch, int pingpongCount) {
        Flux<AgentEvent> step = Flux.just(AgentEvent.status(AGENT_NAME, "质检第 " + round + " 轮"))
                .concatWith(criticAgent.handle(ctx));

        return step.concatWith(Flux.defer(() -> {
            CriticReport report = ctx.getBlackboard().getCriticReport();

            // 分支1：质检通过（或报告为 null 视为通过），结束循环
            if (report == null || report.isPassed()) {
                return Flux.empty();
            }

            // 分支2：已达最大轮次，降级返回当前结果
            if (round >= criticMaxRounds) {
                return Flux.just(AgentEvent.status(AGENT_NAME,
                        "质检超出最大轮次（" + criticMaxRounds + "），降级返回当前结果"));
            }

            // 按 issue 分类决定本轮修复分支
            boolean hasStyle = hasStyleIssue(report);
            boolean hasCoder = hasNonStyleIssue(report);
            String thisBranch = (hasStyle && hasCoder) ? BRANCH_BOTH
                    : hasStyle ? BRANCH_STYLE : BRANCH_CODER;

            // 乒乓抑制：仅当本轮与上一轮在 Style↔Coder 之间单分支来回时累计
            boolean alternation = isStyleCoderAlternation(lastBranch, thisBranch);
            int newPingpong = alternation ? pingpongCount + 1 : 0;
            if (newPingpong >= 2) {
                return Flux.just(AgentEvent.status(AGENT_NAME,
                        "检测到 Style↔Coder 反复修复未收敛，强制收敛降级返回当前结果"));
            }

            // 构建本轮修复动作（统一计为 1 轮）
            Flux<AgentEvent> repair;
            if (BRANCH_BOTH.equals(thisBranch)) {
                repair = Flux.just(AgentEvent.status(AGENT_NAME,
                                "质检未通过，进入第 " + (round + 1) + " 轮修复（先样式后代码）"))
                        .concatWith(styleAgent.handle(ctx))
                        .concatWith(coderAgent.handle(ctx));
            } else if (BRANCH_STYLE.equals(thisBranch)) {
                repair = Flux.just(AgentEvent.status(AGENT_NAME,
                                "质检未通过，进入第 " + (round + 1) + " 轮修复（样式）"))
                        .concatWith(styleAgent.handle(ctx));
            } else {
                repair = Flux.just(AgentEvent.status(AGENT_NAME,
                                "质检未通过，进入第 " + (round + 1) + " 轮修复（代码）"))
                        .concatWith(coderAgent.handle(ctx));
            }

            return repair.concatWith(doCriticRound(ctx, round + 1, thisBranch, newPingpong));
        }));
    }

    /**
     * 是否存在样式类问题：{@code category=="style"} 或 file 以 {@code src/styles/} 开头；
     * 当 category 与 file 均缺失时，退化为对 description 做关键词兜底匹配。
     */
    private boolean hasStyleIssue(CriticReport report) {
        List<CriticReport.Issue> issues = report.getIssues();
        if (issues == null) {
            return false;
        }
        return issues.stream().anyMatch(this::isStyleIssue);
    }

    /**
     * 是否存在非样式类（代码类）问题。
     */
    private boolean hasNonStyleIssue(CriticReport report) {
        List<CriticReport.Issue> issues = report.getIssues();
        if (issues == null || issues.isEmpty()) {
            // 未通过但无明细 issues：默认按代码类重跑 Coder
            return true;
        }
        return issues.stream().anyMatch(i -> !isStyleIssue(i));
    }

    private boolean isStyleIssue(CriticReport.Issue issue) {
        if (issue == null) {
            return false;
        }
        String category = issue.getCategory();
        String file = issue.getFile();
        if (category != null && category.equalsIgnoreCase("style")) {
            return true;
        }
        if (file != null && file.replace('\\', '/').startsWith("src/styles/")) {
            return true;
        }
        // 仅当 category 缺失且 file 为空时，才退化为关键词兜底匹配
        if ((category == null || category.isBlank()) && (file == null || file.isBlank())) {
            String desc = issue.getDescription() == null ? "" : issue.getDescription().toLowerCase();
            return desc.contains("variables.css") || desc.contains("global.css")
                    || desc.contains("token") || desc.contains("typography") || desc.contains("reset");
        }
        return false;
    }

    /**
     * 判断本轮分支是否与上一轮在 Style↔Coder 之间单分支来回（BOTH 不算来回）。
     */
    private boolean isStyleCoderAlternation(String lastBranch, String thisBranch) {
        return (BRANCH_STYLE.equals(lastBranch) && BRANCH_CODER.equals(thisBranch))
                || (BRANCH_CODER.equals(lastBranch) && BRANCH_STYLE.equals(thisBranch));
    }
}
