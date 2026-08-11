package com.yu.yuaifather.tool.system;

import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.model.dto.agent.ProjectBrandContext;
import com.yu.yuaifather.service.ProjectBrandService;
import com.yu.yuaifather.tool.core.SystemTool;
import com.yu.yuaifather.tool.core.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * ProjectProfileTool：项目品牌画像加载（System Tool，零 LLM）。
 * <p>
 * 从 ProjectPreferenceAgent 改名重构而来。
 * <p>
 * 从数据库加载当前项目的品牌画像数据，组装为 ProjectBrandContext 写入 Blackboard。
 */
@Slf4j
@Component
public class ProjectProfileTool implements Agent, SystemTool<Long, ProjectBrandContext> {

    public static final String TOOL_NAME = "ProjectProfileTool";

    @Value("${agent.rerank.enabled:false}")
    private boolean rerankEnabled;

    private final ProjectBrandService projectBrandService;

    @Autowired
    public ProjectProfileTool(ProjectBrandService projectBrandService) {
        this.projectBrandService = projectBrandService;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            if (!rerankEnabled) {
                sink.next(AgentEvent.status(TOOL_NAME, "综合重排未启用，跳过项目品牌加载"));
                sink.complete();
                return;
            }
            sink.next(AgentEvent.status(TOOL_NAME, "加载项目品牌画像"));
            try {
                Long projectId = resolveProjectId(ctx);
                if (projectId == null) {
                    sink.next(AgentEvent.status(TOOL_NAME, "无项目上下文，跳过品牌加载"));
                    sink.complete();
                    return;
                }
                ProjectBrandContext context = projectBrandService.loadProjectBrandContext(projectId);
                ctx.getBlackboard().putProjectBrandContext(context);
                if (context.hasBrandProfile()) {
                    sink.next(AgentEvent.status(TOOL_NAME,
                            "项目品牌加载完成 brand=" + context.getBrandName()
                                    + " industry=" + context.getIndustry()));
                } else {
                    sink.next(AgentEvent.status(TOOL_NAME, "项目尚无品牌画像"));
                }
            } catch (Exception e) {
                log.warn("ProjectProfileTool 加载品牌异常 msg={}", e.getMessage());
                sink.next(AgentEvent.status(TOOL_NAME, "品牌加载异常，降级跳过"));
            }
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * SystemTool 同步执行：根据 projectId 加载项目品牌画像。
     * projectId 从 ToolContext 获取，不由调用方传入。
     */
    @Override
    public ProjectBrandContext execute(Long projectId, ToolContext context) {
        if (!rerankEnabled || projectId == null) {
            return ProjectBrandContext.empty();
        }
        try {
            return projectBrandService.loadProjectBrandContext(projectId);
        } catch (Exception e) {
            log.warn("ProjectProfileTool 同步加载异常 projectId={} msg={}", projectId, e.getMessage());
            return ProjectBrandContext.empty();
        }
    }

    private Long resolveProjectId(AgentContext ctx) {
        if (ctx.getAppId() != null) {
            return ctx.getAppId();
        }
        return null;
    }
}
