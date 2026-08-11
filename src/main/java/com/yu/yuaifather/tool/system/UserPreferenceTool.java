package com.yu.yuaifather.tool.system;

import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.model.dto.agent.UserDesignContext;
import com.yu.yuaifather.service.UserPreferenceService;
import com.yu.yuaifather.tool.core.SystemTool;
import com.yu.yuaifather.tool.core.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * UserPreferenceTool：用户偏好加载（System Tool，零 LLM）。
 * <p>
 * 从 UserPreferenceAgent 改名重构而来。
 * <p>
 * 从数据库加载当前用户的设计偏好画像和权重数据，
 * 组装为 {@link UserDesignContext} 写入 Blackboard。
 */
@Slf4j
@Component
public class UserPreferenceTool implements Agent, SystemTool<Long, UserDesignContext> {

    public static final String TOOL_NAME = "UserPreferenceTool";

    @Value("${agent.personalization.enabled:false}")
    private boolean personalizationEnabled;

    private final UserPreferenceService userPreferenceService;

    @Autowired
    public UserPreferenceTool(UserPreferenceService userPreferenceService) {
        this.userPreferenceService = userPreferenceService;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            if (!personalizationEnabled) {
                sink.next(AgentEvent.status(TOOL_NAME, "个性化偏好未启用"));
                sink.complete();
                return;
            }
            sink.next(AgentEvent.status(TOOL_NAME, "加载用户设计偏好"));
            try {
                Long userId = resolveUserId(ctx);
                if (userId == null) {
                    sink.next(AgentEvent.status(TOOL_NAME, "无用户身份，跳过偏好加载"));
                    sink.complete();
                    return;
                }
                UserDesignContext context = userPreferenceService.loadUserDesignContext(userId);
                ctx.getBlackboard().putUserDesignContext(context);
                if (context.hasPreferences()) {
                    sink.next(AgentEvent.status(TOOL_NAME,
                            "用户偏好完成 styles=" + safeListSize(context.getPreferredStyles())
                                    + " colors=" + safeListSize(context.getPreferredColors())
                                    + " weights=" + safeMapSize(context.getPreferenceWeights())));
                } else {
                    sink.next(AgentEvent.status(TOOL_NAME, "用户尚无偏好数据"));
                }
            } catch (Exception e) {
                log.warn("UserPreferenceTool 加载偏好异常 msg={}", e.getMessage());
                sink.next(AgentEvent.status(TOOL_NAME, "偏好加载异常，降级跳过"));
            }
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * SystemTool 同步执行：根据 userId 加载用户偏好上下文。
     * userId 从 ToolContext 获取（系统注入），不由调用方传入。
     */
    @Override
    public UserDesignContext execute(Long userId, ToolContext context) {
        if (!personalizationEnabled || userId == null) {
            return UserDesignContext.empty();
        }
        try {
            return userPreferenceService.loadUserDesignContext(userId);
        } catch (Exception e) {
            log.warn("UserPreferenceTool 同步加载异常 userId={} msg={}", userId, e.getMessage());
            return UserDesignContext.empty();
        }
    }

    private Long resolveUserId(AgentContext ctx) {
        if (ctx.getLoginUser() != null) {
            return ctx.getLoginUser().getId();
        }
        return null;
    }

    private int safeListSize(java.util.List<?> list) {
        return list == null ? 0 : list.size();
    }

    private int safeMapSize(java.util.Map<?, ?> map) {
        return map == null ? 0 : map.size();
    }
}
