package com.yu.yuaifather.agent.workers;

import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.ai.model.dto.ProjectBrandContext;
import com.yu.yuaifather.service.ProjectBrandService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * ProjectPreferenceAgent：项目品牌偏好加载 Worker（阶段三新增）。
 * <p>
 * 在 UserPreferenceAgent 之后、DesignRetrieveAgent 之前执行，
 * 从数据库加载当前项目的品牌画像数据，组装为 ProjectBrandContext 写入 Blackboard。
 * <p>
 * 本 Agent 不调用 LLM，纯 Java 从 DB 加载数据。
 * <p>
 * 开关：{@code agent.rerank.enabled:false}（默认关闭）。
 */
@Slf4j
@Component
public class ProjectPreferenceAgent implements Agent {

    public static final String AGENT_NAME = "ProjectPreferenceAgent";

    @Value("${agent.rerank.enabled:false}")
    private boolean rerankEnabled;

    private final ProjectBrandService projectBrandService;

    @Autowired
    public ProjectPreferenceAgent(ProjectBrandService projectBrandService) {
        this.projectBrandService = projectBrandService;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            if (!rerankEnabled) {
                sink.next(AgentEvent.status(AGENT_NAME, "综合重排未启用，跳过项目品牌加载"));
                sink.complete();
                return;
            }

            sink.next(AgentEvent.status(AGENT_NAME, "加载项目品牌画像"));

            try {
                Long projectId = resolveProjectId(ctx);
                if (projectId == null) {
                    sink.next(AgentEvent.status(AGENT_NAME, "无项目上下文，跳过品牌加载"));
                    sink.complete();
                    return;
                }

                ProjectBrandContext context = projectBrandService.loadProjectBrandContext(projectId);
                ctx.getBlackboard().putProjectBrandContext(context);

                if (context.hasBrandProfile()) {
                    sink.next(AgentEvent.status(AGENT_NAME,
                            "项目品牌加载完成 brand=" + context.getBrandName()
                                    + " industry=" + context.getIndustry()));
                } else {
                    sink.next(AgentEvent.status(AGENT_NAME, "项目尚无品牌画像"));
                }
            } catch (Exception e) {
                log.warn("ProjectPreferenceAgent 加载品牌异常 projectId={} msg={}",
                        resolveProjectId(ctx), e.getMessage());
                sink.next(AgentEvent.status(AGENT_NAME, "品牌加载异常，降级跳过"));
            }
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Long resolveProjectId(AgentContext ctx) {
        // 当前架构中 appId 充当项目工作目录标识，project_brand_profile.projectId 与 appId 为一对一映射。
        // 注意：若未来 projectId 与 appId 解耦，此处需改为从 Blackboard 或 ctx 获取真实 projectId。
        if (ctx.getAppId() != null) {
            return ctx.getAppId();
        }
        return null;
    }
}
